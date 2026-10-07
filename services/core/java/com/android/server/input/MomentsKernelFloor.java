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

package com.android.server.input;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.SensorPrivacyManager.Sensors;
import android.hardware.SensorPrivacyManagerInternal;
import android.os.Handler;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The Moments switch's kernel floor: the kernel blocks the built-in microphones and cameras while
 * the switch is on, if it was armed at boot.
 *
 * <ul>
 *   <li>The kernel learns its policy once per boot: init writes {@link #POLICY_PROPERTY} to it
 *       early in boot and the kernel seals it. This class keeps that property equal to the
 *       system user's Moments action, so a change reaches the kernel at the next boot.
 *   <li>While the kernel blocks the microphone, Android shows it as a hardware toggle: the
 *       "unblock" prompt then points to the switch, as on devices with a mute switch. Only the
 *       kernel's own report counts, re-read at every switch report: Android's view of the switch
 *       can differ (injected input events), and then the software toggle stays in charge, so
 *       Android never claims a kernel block that is not there.
 *   <li>{@link #ENFORCED_PROPERTY} tells Settings what the kernel enforces this boot.
 * </ul>
 *
 * <p>The kernel's state comes from {@code config_momentsKernelFloorPath} (the switch driver's
 * {@code state} file); empty (the default) turns all of this off. Changes arrive on the input service's handler.
 */
final class MomentsKernelFloor {
    private static final String TAG = "MomentsKernelFloor";

    /** What init writes to the kernel at the next boot: a mask of the bits below. */
    static final String POLICY_PROPERTY = "persist.diamaneos.privacy_switch.policy";
    /** What the kernel enforces this boot: a mask of the bits below. */
    static final String ENFORCED_PROPERTY = "diamaneos.privacy_switch.enforced";

    /** The kernel's sensor bits (include/linux/diamaneos_privsw.h). */
    static final int KERNEL_MIC = 1;
    static final int KERNEL_CAMERA = 1 << 1;

    /** What the kernel blocks, as one platform hook. Separate for tests. */
    interface Platform {
        /** The kernel's state line, or null when it cannot be read. */
        String readState();
        int[] userIds();
        /** {@code sensor}: a {@link Sensors} value. */
        void setPhysicalToggle(int userId, int sensor, boolean blocked);
        /** The system user's Moments action, or -1 when unset. */
        int momentsAction();
        void setProperty(String key, String value);
        void watch(Runnable onActionChanged, java.util.function.IntConsumer onUserSwitched);
        /** Tells the user that only Android blocks the microphone until a restart. */
        void postAndroidOnlyMicNotice();
        long elapsedRealtime();
        void cancelAndroidOnlyMicNotice();
    }

    private final Platform mPlatform;
    private final boolean mEnabled;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private int mEnforced;
    @GuardedBy("mLock")
    private int mBlocked;
    @GuardedBy("mLock")
    private boolean mEnforcedPublished;
    @GuardedBy("mLock")
    private Boolean mSwitchOn;
    @GuardedBy("mLock")
    private boolean mMicApplied;
    @GuardedBy("mLock")
    private boolean mCameraApplied;
    @GuardedBy("mLock")
    private String mPolicy;
    @GuardedBy("mLock")
    private int mAction = -1;
    // The Android-only notice: posted each time the switch goes down, at most once a minute;
    // cancelled when the choice changes.
    @GuardedBy("mLock")
    private long mNoticeAt = Long.MIN_VALUE;
    @GuardedBy("mLock")
    private boolean mNoticeShowing;

    MomentsKernelFloor(Context context, Handler handler) {
        this(new SystemPlatform(context, handler,
                context.getResources().getString(R.string.config_momentsKernelFloorPath)),
                !TextUtils.isEmpty(context.getResources()
                        .getString(R.string.config_momentsKernelFloorPath)));
    }

    @VisibleForTesting
    MomentsKernelFloor(Platform platform, boolean enabled) {
        mPlatform = platform;
        mEnabled = enabled;
    }

    boolean isEnabled() {
        return mEnabled;
    }

    /** Starts mirroring the action and reads what the kernel enforces. */
    void systemRunning() {
        if (!mEnabled) {
            return;
        }
        mirrorPolicy();
        synchronized (mLock) {
            refreshKernelLocked();
        }
        mPlatform.watch(this::mirrorPolicy, this::onUserSwitched);
    }

    /**
     * A report of the switch, repeats included. {@code on} is Android's view (Moments on), kept
     * for dumps only: the hardware toggle follows what the kernel blocks. Handler thread.
     */
    void onSwitchChanged(boolean on) {
        if (!mEnabled) {
            return;
        }
        synchronized (mLock) {
            boolean wentDown = on && !Boolean.TRUE.equals(mSwitchOn);
            mSwitchOn = on;
            refreshKernelLocked();
            applyLocked();
            if (wentDown) {
                maybePostNoticeLocked();
            }
        }
    }

    /**
     * With "Camera and microphone off" chosen but the kernel not blocking the microphone this
     * boot (sealed without it), only Android blocks it while the switch is down. Say so each time
     * the switch goes down, at most once a minute; a restart arms the kernel. What the kernel
     * enforces comes from its own state file, never from Android's view.
     */
    @VisibleForTesting
    static boolean androidOnlyMicBlock(int action, Boolean switchOn, int enforced) {
        return action == Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
                && Boolean.TRUE.equals(switchOn)
                && (enforced & KERNEL_MIC) == 0;
    }

    @GuardedBy("mLock")
    private void maybePostNoticeLocked() {
        if (!androidOnlyMicBlock(mAction, mSwitchOn, mEnforced)) {
            return;
        }
        long now = mPlatform.elapsedRealtime();
        if (mNoticeAt != Long.MIN_VALUE && now - mNoticeAt < NOTICE_MIN_GAP_MS) {
            return;
        }
        mNoticeAt = now;
        mNoticeShowing = true;
        mPlatform.postAndroidOnlyMicNotice();
    }

    @GuardedBy("mLock")
    private void updateNoticeLocked() {
        if (mNoticeShowing && mAction != Settings.Secure.MOMENTS_ACTION_SENSORS_OFF) {
            // The choice changed: the note no longer applies.
            mNoticeShowing = false;
            mPlatform.cancelAndroidOnlyMicNotice();
        }
    }

    /**
     * Parses the switch driver's state line ("sealed=1 policy=0x3 ... enforced=0x1 ...
     * blocked=0x3"); returns {enforced, blocked}, or null when a field is missing or malformed.
     */
    @VisibleForTesting
    static int[] parseState(String line) {
        if (line == null) {
            return null;
        }
        Map<String, String> fields = new HashMap<>();
        for (String token : line.trim().split("\\s+")) {
            int eq = token.indexOf('=');
            if (eq > 0) {
                fields.put(token.substring(0, eq), token.substring(eq + 1));
            }
        }
        try {
            int enforced = Integer.decode(fields.get("enforced"));
            int blocked = Integer.decode(fields.get("blocked"));
            return enforced < 0 || blocked < 0 ? null : new int[] {enforced, blocked};
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }

    @GuardedBy("mLock")
    private void refreshKernelLocked() {
        int[] state = parseState(mPlatform.readState());
        if (state == null) {
            Slog.w(TAG, "Cannot read the kernel's state");
            // Claim nothing: no kernel block, nothing enforced.
            state = new int[] {0, 0};
        }
        int enforced = state[0];
        if (enforced != mEnforced) {
            Slog.i(TAG, "Kernel blocks " + maskToString(enforced) + " while the switch is on");
        }
        if (!mEnforcedPublished || enforced != mEnforced) {
            mPlatform.setProperty(ENFORCED_PROPERTY, Integer.toString(enforced));
            mEnforcedPublished = true;
        }
        mEnforced = enforced;
        mBlocked = state[1] & enforced;
    }

    @GuardedBy("mLock")
    private void applyLocked() {
        mMicApplied = applySensorLocked(Sensors.MICROPHONE, KERNEL_MIC, mMicApplied);
        mCameraApplied = applySensorLocked(Sensors.CAMERA, KERNEL_CAMERA, mCameraApplied);
    }

    // Returns whether the hardware toggle is now set for the sensor.
    @GuardedBy("mLock")
    private boolean applySensorLocked(int sensor, int kernelBit, boolean applied) {
        boolean blocked = (mBlocked & kernelBit) != 0;
        if (blocked == applied) {
            return applied;
        }
        // Turning the hardware toggle off also turns the software one off, so only do it after
        // turning it on.
        for (int userId : mPlatform.userIds()) {
            mPlatform.setPhysicalToggle(userId, sensor, blocked);
        }
        Slog.i(TAG, "Kernel " + (blocked ? "blocks" : "releases") + " the "
                + (sensor == Sensors.CAMERA ? "camera" : "microphone"));
        return blocked;
    }

    private void onUserSwitched(int userId) {
        synchronized (mLock) {
            if (mMicApplied) {
                mPlatform.setPhysicalToggle(userId, Sensors.MICROPHONE, true);
            }
            if (mCameraApplied) {
                mPlatform.setPhysicalToggle(userId, Sensors.CAMERA, true);
            }
        }
    }

    // The system user's choice; the kernel floor is device-wide.
    private void mirrorPolicy() {
        int action = mPlatform.momentsAction();
        String policy = action == Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
                ? Integer.toString(KERNEL_MIC | KERNEL_CAMERA) : "0";
        synchronized (mLock) {
            mAction = action;
            updateNoticeLocked();
            if (policy.equals(mPolicy)) {
                return;
            }
            mPolicy = policy;
        }
        mPlatform.setProperty(POLICY_PROPERTY, policy);
        Slog.i(TAG, "Kernel policy for the next boot: " + policy);
    }

    void dump(IndentingPrintWriter pw) {
        pw.println("Moments kernel floor:" + (mEnabled ? "" : " off"));
        if (!mEnabled) {
            return;
        }
        pw.increaseIndent();
        synchronized (mLock) {
            pw.println("enforced: " + maskToString(mEnforced));
            pw.println("blocked now: " + maskToString(mBlocked));
            pw.println("switch on (Android's view): " + mSwitchOn);
            pw.println("hardware mic toggle set: " + mMicApplied);
            pw.println("hardware camera toggle set: " + mCameraApplied);
            pw.println("policy for next boot: " + mPolicy);
            pw.println("android-only notice last posted: "
                    + (mNoticeAt == Long.MIN_VALUE ? "never" : mNoticeAt));
        }
        pw.decreaseIndent();
    }

    private static String maskToString(int mask) {
        if ((mask & (KERNEL_MIC | KERNEL_CAMERA)) == 0) {
            return "nothing";
        }
        StringBuilder sb = new StringBuilder();
        if ((mask & KERNEL_MIC) != 0) {
            sb.append("microphone");
        }
        if ((mask & KERNEL_CAMERA) != 0) {
            sb.append(sb.length() > 0 ? " and camera" : "camera");
        }
        return sb.toString();
    }

    private static final String ACTION_MOMENTS_SETTINGS =
            "de.diamaneos.settings.MOMENTS_SWITCH_SETTINGS";
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final int NOTICE_ID = 1;
    @VisibleForTesting
    static final long NOTICE_MIN_GAP_MS = 60_000;
    private static final String NOTICE_CHANNEL = "MOMENTS_SWITCH_PROTECTION";

    private static final class SystemPlatform implements Platform {
        private final Context mContext;
        private final Handler mHandler;
        private final String mPath;

        SystemPlatform(Context context, Handler handler, String path) {
            mContext = context;
            mHandler = handler;
            mPath = path;
        }

        @Override
        public String readState() {
            try {
                return Files.readString(Path.of(mPath), StandardCharsets.US_ASCII);
            } catch (IOException | SecurityException e) {
                return null;
            }
        }

        @Override
        public int[] userIds() {
            return LocalServices.getService(UserManagerInternal.class).getUserIds();
        }

        @Override
        public void setPhysicalToggle(int userId, int sensor, boolean blocked) {
            LocalServices.getService(SensorPrivacyManagerInternal.class)
                    .setPhysicalToggleSensorPrivacy(userId, sensor, blocked);
        }

        @Override
        public int momentsAction() {
            return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.TALLY_MOMENTS_ACTION, -1, UserHandle.USER_SYSTEM);
        }

        @Override
        public void setProperty(String key, String value) {
            try {
                SystemProperties.set(key, value);
            } catch (RuntimeException e) {
                Slog.e(TAG, "Cannot set " + key, e);
            }
        }

        @Override
        public void postAndroidOnlyMicNotice() {
            Intent intent = new Intent(ACTION_MOMENTS_SETTINGS).setPackage(SETTINGS_PACKAGE);
            PendingIntent pending = PendingIntent.getActivityAsUser(mContext, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE, null, UserHandle.CURRENT);
            NotificationManager nm = mContext.getSystemService(NotificationManager.class);
            // Its own channel, high importance: the default sound and a heads-up, within DND.
            nm.createNotificationChannel(new NotificationChannel(NOTICE_CHANNEL,
                    mContext.getString(R.string.moments_protection_channel),
                    NotificationManager.IMPORTANCE_HIGH));
            Notification notification =
                    new Notification.Builder(mContext, NOTICE_CHANNEL)
                            .setSmallIcon(R.drawable.ic_mic_blocked)
                            .setContentTitle(mContext.getString(
                                    R.string.moments_android_only_mic_title))
                            .setContentText(mContext.getString(
                                    R.string.moments_android_only_mic_text))
                            .setStyle(new Notification.BigTextStyle().bigText(mContext.getString(
                                    R.string.moments_android_only_mic_text)))
                            .setContentIntent(pending)
                            .setAutoCancel(true)
                            .setLocalOnly(true)
                            .build();
            nm.notifyAsUser(TAG, NOTICE_ID, notification, UserHandle.CURRENT);
        }

        @Override
        public long elapsedRealtime() {
            return SystemClock.elapsedRealtime();
        }

        @Override
        public void cancelAndroidOnlyMicNotice() {
            mContext.getSystemService(NotificationManager.class)
                    .cancelAsUser(TAG, NOTICE_ID, UserHandle.CURRENT);
        }

        @Override
        public void watch(Runnable onActionChanged,
                java.util.function.IntConsumer onUserSwitched) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.TALLY_MOMENTS_ACTION),
                    false /* notifyForDescendants */,
                    new ContentObserver(mHandler) {
                        @Override
                        public void onChange(boolean selfChange) {
                            onActionChanged.run();
                        }
                    }, UserHandle.USER_SYSTEM);
            mContext.registerReceiverAsUser(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                            UserHandle.USER_NULL);
                    if (userId != UserHandle.USER_NULL) {
                        onUserSwitched.accept(userId);
                    }
                }
            }, UserHandle.ALL, new IntentFilter(Intent.ACTION_USER_SWITCHED), null, mHandler);
        }
    }
}
