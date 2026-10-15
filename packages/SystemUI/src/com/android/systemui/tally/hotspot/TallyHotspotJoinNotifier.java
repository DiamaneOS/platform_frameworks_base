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

package com.android.systemui.tally.hotspot;

import static android.net.TetheringManager.TETHERING_WIFI;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.icu.text.ListFormatter;
import android.net.TetheredClient;
import android.net.TetheringManager;
import android.net.Uri;
import android.net.wifi.WifiClient;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.BidiFormatter;
import android.util.PluralsMessageFormatter;

import androidx.annotation.NonNull;

import com.android.settingslib.wifi.WifiUtils;
import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

/**
 * Tells the user when a device joins the Wi-Fi hotspot, if they turned that on
 * ({@link Settings.Secure#TALLY_HOTSPOT_JOIN_NOTICE}, off by default).
 *
 * <p>It lives in SystemUI, which runs for as long as the hotspot does. The addresses and names
 * are other people's: they stay in memory and are never logged, dumped or stored.
 */
@SysUISingleton
public class TallyHotspotJoinNotifier implements CoreStartable {
    /** Silent: a known device came back. */
    static final String CHANNEL_ID = "tally_hotspot_join";
    /** With sound: a device is new in this hotspot session. */
    static final String NEW_DEVICE_CHANNEL_ID = "tally_hotspot_join_new";
    private static final String NOTICE_TAG = "tally_hotspot_join";
    private static final int NOTICE_ID = 1;
    private static final String SETTINGS_PACKAGE = "com.android.settings";

    private final Context mContext;
    private final DelayableExecutor mBgExecutor;
    private final UserTracker mUserTracker;
    private final WifiManager mWifiManager;
    private final TetheringManager mTetheringManager;
    private final UserManager mUserManager;
    private final NotificationManager mNotificationManager;

    // Background thread only.
    private final TallyHotspotJoins mJoins = new TallyHotspotJoins();
    /** Claimed names by MAC address. */
    private Map<String, String> mClaimedNames = new HashMap<>();
    private boolean mListening;
    private int mUser = UserHandle.USER_NULL;
    private Runnable mCancelTimer;

    private final WifiManager.SoftApCallback mSoftApCallback = new WifiManager.SoftApCallback() {
        @Override
        public void onStateChanged(int state, int failureReason) {
            if (!mListening) return;
            if (state != WifiManager.WIFI_AP_STATE_ENABLED) {
                mJoins.onHotspotOff();
                mClaimedNames = new HashMap<>();
            }
        }

        @Override
        public void onConnectedClientsChanged(@NonNull List<WifiClient> clients) {
            if (!mListening) return;
            final List<String> macs = new ArrayList<>(clients.size());
            for (WifiClient client : clients) {
                macs.add(client.getMacAddress().toString());
            }
            schedule(mJoins.onClients(macs, SystemClock.elapsedRealtime()));
        }
    };

    private final TetheringManager.TetheringEventCallback mLeaseCallback =
            new TetheringManager.TetheringEventCallback() {
                @Override
                public void onClientsChanged(@NonNull Collection<TetheredClient> clients) {
                    if (!mListening) return;
                    final Map<String, String> names = new HashMap<>();
                    for (TetheredClient client : clients) {
                        if (client.getTetheringType() != TETHERING_WIFI) continue;
                        final String name = newestClaimedName(client);
                        if (name != null) {
                            names.put(TallyHotspotJoins.normalize(
                                    client.getMacAddress().toString()), name);
                        }
                    }
                    mClaimedNames = names;
                }
            };

    // The Uri form of onChange is the one called in a process that is not the system uid.
    private final ContentObserver mSwitchObserver =
            new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    mBgExecutor.execute(TallyHotspotJoinNotifier.this::update);
                }
            };

    private final UserTracker.Callback mUserCallback = new UserTracker.Callback() {
        @Override
        public void onUserChanged(int newUser, @NonNull Context userContext) {
            update();
        }
    };

    @Inject
    public TallyHotspotJoinNotifier(
            @Application Context context,
            @Background DelayableExecutor bgExecutor,
            UserTracker userTracker) {
        mContext = context;
        mBgExecutor = bgExecutor;
        mUserTracker = userTracker;
        mWifiManager = context.getSystemService(WifiManager.class);
        mTetheringManager = context.getSystemService(TetheringManager.class);
        mUserManager = context.getSystemService(UserManager.class);
        mNotificationManager = context.getSystemService(NotificationManager.class);
    }

    @Override
    public void start() {
        if (mWifiManager == null || mTetheringManager == null || mUserManager == null
                || mNotificationManager == null) {
            return;
        }
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.TALLY_HOTSPOT_JOIN_NOTICE),
                false, mSwitchObserver, UserHandle.USER_ALL);
        mUserTracker.addCallback(mUserCallback, mBgExecutor);
        mBgExecutor.execute(this::update);
    }

    /** Starts or stops listening to match the switch and the current user. */
    private void update() {
        final int user = mUserTracker.getUserId();
        final boolean wanted = isWanted(user);
        if (mListening && (!wanted || user != mUser)) {
            stopListening();
        }
        if (wanted && !mListening) {
            startListening(user);
        }
    }

    /** Only a user who may set up the hotspot learns who joins, as for the hotspot tile. */
    private boolean isWanted(int user) {
        if (Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.TALLY_HOTSPOT_JOIN_NOTICE, 0, user) != 1) {
            return false;
        }
        return mUserManager.isUserAdmin(user)
                && !mUserManager.hasUserRestrictionForUser(
                        UserManager.DISALLOW_CONFIG_TETHERING, UserHandle.of(user));
    }

    private void startListening(int user) {
        mUser = user;
        mListening = true;
        mJoins.start();
        // Both callbacks report the current state right after registering.
        mWifiManager.registerSoftApCallback(mBgExecutor, mSoftApCallback);
        mTetheringManager.registerTetheringEventCallback(mBgExecutor, mLeaseCallback);
    }

    private void stopListening() {
        mListening = false;
        mWifiManager.unregisterSoftApCallback(mSoftApCallback);
        mTetheringManager.unregisterTetheringEventCallback(mLeaseCallback);
        mJoins.stop();
        mClaimedNames = new HashMap<>();
        if (mCancelTimer != null) {
            mCancelTimer.run();
            mCancelTimer = null;
        }
        if (Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.TALLY_HOTSPOT_JOIN_NOTICE, 0, mUser) != 1) {
            // Switched off: a notice that still shows goes too.
            mNotificationManager.cancelAsUser(NOTICE_TAG, NOTICE_ID, UserHandle.of(mUser));
        }
        mUser = UserHandle.USER_NULL;
    }

    private void schedule(long dueAt) {
        if (dueAt == TallyHotspotJoins.NONE || mCancelTimer != null) return;
        final long delay = Math.max(0, dueAt - SystemClock.elapsedRealtime());
        mCancelTimer = mBgExecutor.executeDelayed(this::onTimer, delay);
    }

    private void onTimer() {
        mCancelTimer = null;
        if (!mListening) return;
        final TallyHotspotJoins.Notice notice =
                mJoins.takeNotice(SystemClock.elapsedRealtime(), mClaimedNames);
        if (notice == null) {
            schedule(mJoins.dueAt());
            return;
        }
        post(notice, mUser);
    }

    private void post(TallyHotspotJoins.Notice notice, int user) {
        // Both at once, so the user can change either from the first notice on.
        mNotificationManager.createNotificationChannels(List.of(
                new NotificationChannel(CHANNEL_ID,
                        mContext.getString(R.string.tally_hotspot_join_channel_returned),
                        NotificationManager.IMPORTANCE_LOW),
                new NotificationChannel(NEW_DEVICE_CHANNEL_ID,
                        mContext.getString(R.string.tally_hotspot_join_channel_new),
                        NotificationManager.IMPORTANCE_DEFAULT)));
        final String channel = notice.alerts ? NEW_DEVICE_CHANNEL_ID : CHANNEL_ID;

        final String title = plural(R.string.tally_hotspot_join_title, notice.joined, null);
        final String connected =
                WifiUtils.getWifiTetherSummaryForConnectedDevices(mContext, notice.connected);
        final boolean named = !notice.names.isEmpty() && mContext.getResources().getBoolean(
                R.bool.tally_hotspot_join_notice_shows_names);
        final String text = named
                ? plural(R.string.tally_hotspot_join_names, notice.names.size(),
                        listOf(notice.names))
                : connected;

        final UserHandle handle = UserHandle.of(user);
        final PendingIntent openHotspotPage = PendingIntent.getActivityAsUser(mContext, 0,
                new Intent(Settings.ACTION_WIFI_TETHER_SETTING).setPackage(SETTINGS_PACKAGE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT, null, handle);

        // For a lock screen that hides sensitive content: no names.
        final Notification withoutNames = new Notification.Builder(mContext, channel)
                .setSmallIcon(R.drawable.ic_hotspot)
                .setContentTitle(title)
                .setContentText(connected)
                .build();
        // Not only-alert-once: a notice may replace one that still shows, and must sound too.
        final Notification notification = new Notification.Builder(mContext, channel)
                .setSmallIcon(R.drawable.ic_hotspot)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openHotspotPage)
                .setAutoCancel(true)
                .setLocalOnly(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(withoutNames)
                .build();
        mNotificationManager.notifyAsUser(NOTICE_TAG, NOTICE_ID, notification, handle);
    }

    private String plural(int resId, int count, String names) {
        final Map<String, Object> arguments = new HashMap<>();
        arguments.put("count", count);
        if (names != null) {
            arguments.put("names", names);
        }
        return PluralsMessageFormatter.format(mContext.getResources(), arguments, resId);
    }

    /** "A, B and C", each name kept in its own writing direction. */
    private static String listOf(List<String> names) {
        final BidiFormatter bidi = BidiFormatter.getInstance();
        final List<String> wrapped = new ArrayList<>(names.size());
        for (String name : names) {
            wrapped.add(bidi.unicodeWrap(name));
        }
        return ListFormatter.getInstance().format(wrapped);
    }

    /** The host name sent for the newest address lease, or null. */
    private static String newestClaimedName(TetheredClient client) {
        String name = null;
        long newest = Long.MIN_VALUE;
        for (TetheredClient.AddressInfo info : client.getAddresses()) {
            final String hostname = info.getHostname();
            if (hostname == null || hostname.isEmpty()) continue;
            final long expiry = info.getAddress().getExpirationTime();
            if (name == null || expiry > newest) {
                name = hostname;
                newest = expiry;
            }
        }
        return name;
    }
}
