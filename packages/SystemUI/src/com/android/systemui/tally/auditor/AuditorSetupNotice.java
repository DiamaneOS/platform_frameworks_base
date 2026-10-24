/*
 * Copyright (C) 2026 The DiamaneOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.tally.auditor;

import static android.service.notification.NotificationListenerService.REASON_CLICK;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.android.systemui.CoreStartable;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserFileManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.util.NotificationChannels;
import com.android.systemui.util.concurrency.DelayableExecutor;

import javax.inject.Inject;

/**
 * Offers Auditor in a notification when the primary user finishes setup, and again after each
 * restart until the user taps it, swipes it away or pairs the phone in Auditor. Tapping it opens
 * Auditor's add-this-phone screen. Nothing opens by itself. The Setup Wizard cannot post it: it
 * disables itself at the end, and that removes its notifications.
 */
@SysUISingleton
public class AuditorSetupNotice implements CoreStartable {
    private static final String TAG = "AuditorSetupNotice";
    private static final String AUDITOR = AuditorSetupTrigger.AUDITOR;
    private static final String ACTION_ADD_THIS_PHONE =
            "de.diamaneos.auditor.action.ADD_THIS_PHONE";
    private static final String ACTION_DISMISSED =
            "com.android.systemui.tally.auditor.action.NOTICE_DISMISSED";
    private static final String NOTICE_TAG = "tally_auditor";
    private static final int NOTICE_ID = 1;
    // In SystemUI's device-protected storage, so it can be read before the first unlock.
    private static final String PREFS = "tally_auditor_notice";
    private static final String KEY_STATE = "state";

    private final Context mContext;
    private final DelayableExecutor mBgExecutor;
    private final UserFileManager mUserFileManager;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final CommonNotifCollection mNotifCollection;
    // Background thread only.
    private final AuditorSetupTrigger mTrigger = new AuditorSetupTrigger();
    private SharedPreferences mPrefs;

    private final ContentObserver mSetupObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    mBgExecutor.execute(AuditorSetupNotice.this::onSetupChanged);
                }
            };

    // A swipe or Clear all sends the delete intent. A tap does not.
    private final BroadcastReceiver mDismissReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onUserDone();
        }
    };

    private final NotifCollectionListener mTapListener = new NotifCollectionListener() {
        @Override
        public void onEntryRemoved(NotificationEntry entry, int reason) {
            if (reason == REASON_CLICK && isNotice(entry.getSbn())) {
                mBgExecutor.execute(AuditorSetupNotice.this::onUserDone);
            }
        }

        // Auditor posts this after each successful remote pairing or verification, also for a
        // phone paired before the notice was offered. Only Auditor can post as its package.
        @Override
        public void onEntryAdded(NotificationEntry entry) {
            onAuditorNotification(entry.getSbn());
        }

        @Override
        public void onEntryUpdated(NotificationEntry entry) {
            onAuditorNotification(entry.getSbn());
        }
    };

    @Inject
    public AuditorSetupNotice(
            @Application Context context,
            @Background DelayableExecutor bgExecutor,
            UserFileManager userFileManager,
            BroadcastDispatcher broadcastDispatcher,
            CommonNotifCollection notifCollection) {
        mContext = context;
        mBgExecutor = bgExecutor;
        mUserFileManager = userFileManager;
        mBroadcastDispatcher = broadcastDispatcher;
        mNotifCollection = notifCollection;
    }

    @Override
    public void start() {
        mNotifCollection.addCollectionListener(mTapListener);
        mBroadcastDispatcher.registerReceiver(mDismissReceiver,
                new IntentFilter(ACTION_DISMISSED), mBgExecutor, UserHandle.SYSTEM,
                Context.RECEIVER_NOT_EXPORTED, null);
        mBgExecutor.execute(() -> {
            mPrefs = mUserFileManager.getSharedPreferences(PREFS, Context.MODE_PRIVATE,
                    UserHandle.USER_SYSTEM);
            final AuditorSetupTrigger.Start next = mTrigger.start(
                    mPrefs.getInt(KEY_STATE, AuditorSetupTrigger.NEW), isSetUp());
            if (next == AuditorSetupTrigger.Start.POST) {
                post();
            } else if (next == AuditorSetupTrigger.Start.WATCH) {
                mContext.getContentResolver().registerContentObserver(
                        Settings.Secure.getUriFor(Settings.Secure.USER_SETUP_COMPLETE), false,
                        mSetupObserver, UserHandle.USER_SYSTEM);
                // Setup may have finished before the observer was registered.
                onSetupChanged();
            }
        });
    }

    private boolean isSetUp() {
        return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 0, UserHandle.USER_SYSTEM) != 0;
    }

    private boolean isNotice(StatusBarNotification sbn) {
        return mContext.getPackageName().equals(sbn.getPackageName())
                && NOTICE_TAG.equals(sbn.getTag()) && sbn.getId() == NOTICE_ID
                && UserHandle.SYSTEM.equals(sbn.getUser());
    }

    private void onAuditorNotification(StatusBarNotification sbn) {
        if (UserHandle.SYSTEM.equals(sbn.getUser()) && AuditorSetupTrigger.isRemoteVerified(
                sbn.getPackageName(), sbn.getNotification().getChannelId())) {
            mBgExecutor.execute(this::onPaired);
        }
    }

    private void onPaired() {
        if (!mTrigger.onUserDone()) return;
        saveState();
        mContext.getSystemService(NotificationManager.class)
                .cancelAsUser(NOTICE_TAG, NOTICE_ID, UserHandle.SYSTEM);
        Log.i(TAG, "notice done, the phone is paired in Auditor");
    }

    private void onSetupChanged() {
        if (!mTrigger.onSetupChanged(isSetUp())) return;
        mContext.getContentResolver().unregisterContentObserver(mSetupObserver);
        saveState();
        post();
    }

    private void onUserDone() {
        if (!mTrigger.onUserDone()) return;
        saveState();
        Log.i(TAG, "notice done");
    }

    private void saveState() {
        // commit, not apply: a restart may follow right away.
        mPrefs.edit().putInt(KEY_STATE, mTrigger.state()).commit();
    }

    private void post() {
        final Intent intent = new Intent(ACTION_ADD_THIS_PHONE).setPackage(AUDITOR);
        // Both flags: after a restart this runs before the first unlock.
        final ResolveInfo info = mContext.getPackageManager().resolveActivity(intent,
                PackageManager.MATCH_DIRECT_BOOT_AWARE | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
        if (info == null || info.activityInfo == null
                || !AUDITOR.equals(info.activityInfo.packageName)) {
            Log.i(TAG, "no Auditor, no notice");
            return;
        }
        intent.setClassName(AUDITOR, info.activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final PendingIntent open = PendingIntent.getActivityAsUser(mContext, 0, intent,
                PendingIntent.FLAG_IMMUTABLE, null, UserHandle.SYSTEM);
        final PendingIntent dismissed = PendingIntent.getBroadcastAsUser(mContext, 0,
                new Intent(ACTION_DISMISSED).setPackage(mContext.getPackageName()),
                PendingIntent.FLAG_IMMUTABLE, UserHandle.SYSTEM);
        final Notification notification =
                new Notification.Builder(mContext, NotificationChannels.SETUP)
                        .setSmallIcon(R.drawable.tally_auditor_icon)
                        .setContentTitle(mContext.getString(R.string.tally_auditor_notice_title))
                        .setContentIntent(open)
                        .setDeleteIntent(dismissed)
                        .setAutoCancel(true)
                        .setOnlyAlertOnce(true)
                        .setLocalOnly(true)
                        .build();
        mContext.getSystemService(NotificationManager.class)
                .notifyAsUser(NOTICE_TAG, NOTICE_ID, notification, UserHandle.SYSTEM);
        Log.i(TAG, "notice posted");
    }
}
