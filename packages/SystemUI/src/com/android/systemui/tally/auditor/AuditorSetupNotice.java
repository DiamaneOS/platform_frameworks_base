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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.res.R;
import com.android.systemui.util.NotificationChannels;
import com.android.systemui.util.concurrency.DelayableExecutor;

import javax.inject.Inject;

/**
 * Offers Auditor in one notification when the primary user finishes setup. Tapping it opens
 * Auditor's add-this-phone screen. Nothing opens by itself. The Setup Wizard cannot post it: it
 * disables itself at the end, and that removes its notifications.
 */
@SysUISingleton
public class AuditorSetupNotice implements CoreStartable {
    private static final String TAG = "AuditorSetupNotice";
    private static final String AUDITOR = "de.diamaneos.auditor";
    private static final String ACTION_ADD_THIS_PHONE =
            "de.diamaneos.auditor.action.ADD_THIS_PHONE";
    private static final String NOTICE_TAG = "tally_auditor";
    private static final int NOTICE_ID = 1;

    private final Context mContext;
    private final DelayableExecutor mBgExecutor;
    // Background thread only.
    private final AuditorSetupTrigger mTrigger = new AuditorSetupTrigger();

    private final ContentObserver mSetupObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    mBgExecutor.execute(AuditorSetupNotice.this::onSetupChanged);
                }
            };

    @Inject
    public AuditorSetupNotice(
            @Application Context context,
            @Background DelayableExecutor bgExecutor) {
        mContext = context;
        mBgExecutor = bgExecutor;
    }

    @Override
    public void start() {
        mBgExecutor.execute(() -> {
            if (!mTrigger.start(isSetUp())) return;
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.USER_SETUP_COMPLETE), false,
                    mSetupObserver, UserHandle.USER_SYSTEM);
            // Setup may have finished before the observer was registered.
            onSetupChanged();
        });
    }

    private boolean isSetUp() {
        return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 0, UserHandle.USER_SYSTEM) != 0;
    }

    private void onSetupChanged() {
        if (!mTrigger.onSetupChanged(isSetUp())) return;
        mContext.getContentResolver().unregisterContentObserver(mSetupObserver);
        post();
    }

    private void post() {
        final Intent intent = new Intent(ACTION_ADD_THIS_PHONE).setPackage(AUDITOR);
        final ResolveInfo info = mContext.getPackageManager().resolveActivity(intent, 0);
        if (info == null || info.activityInfo == null
                || !AUDITOR.equals(info.activityInfo.packageName)) {
            Log.i(TAG, "no Auditor, no notice");
            return;
        }
        intent.setClassName(AUDITOR, info.activityInfo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final PendingIntent open = PendingIntent.getActivityAsUser(mContext, 0, intent,
                PendingIntent.FLAG_IMMUTABLE, null, UserHandle.SYSTEM);
        final Notification notification =
                new Notification.Builder(mContext, NotificationChannels.SETUP)
                        .setSmallIcon(R.drawable.tally_auditor_icon)
                        .setContentTitle(mContext.getString(R.string.tally_auditor_notice_title))
                        .setContentIntent(open)
                        .setAutoCancel(true)
                        .setLocalOnly(true)
                        .build();
        mContext.getSystemService(NotificationManager.class)
                .notifyAsUser(NOTICE_TAG, NOTICE_ID, notification, UserHandle.SYSTEM);
        Log.i(TAG, "notice posted");
    }
}
