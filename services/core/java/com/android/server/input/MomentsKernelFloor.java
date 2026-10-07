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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.SensorPrivacyManager.Sensors;
import android.hardware.SensorPrivacyManagerInternal;
import android.os.Handler;
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

/**
 * The Moments switch's kernel floor: the kernel blocks the built-in microphones while the switch
 * is on, if it was armed at boot.
 *
 * <ul>
 *   <li>The kernel learns its policy once per boot: init writes {@link #POLICY_PROPERTY} to it
 *       early in boot and the kernel seals it. This class keeps that property equal to the
 *       system user's Moments action, so a change reaches the kernel at the next boot.
 *   <li>While the kernel blocks the microphone, Android shows it as a hardware toggle: the
 *       "unblock" prompt then points to the switch, as on devices with a mute switch. The
 *       software toggle path stays for when the floor is not armed.
 *   <li>{@link #ENFORCED_PROPERTY} tells Settings what the kernel enforces this boot.
 * </ul>
 *
 * <p>The kernel's mask comes from {@code config_momentsKernelFloorPath}; empty (the default)
 * turns all of this off. Changes arrive on the input service's handler.
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
        /** The kernel's enforced mask, or -1 when it cannot be read. */
        int readEnforced();
        int[] userIds();
        void setPhysicalMicToggle(int userId, boolean blocked);
        /** The system user's Moments action, or -1 when unset. */
        int momentsAction();
        void setProperty(String key, String value);
        void watch(Runnable onActionChanged, java.util.function.IntConsumer onUserSwitched);
    }

    private final Platform mPlatform;
    private final boolean mEnabled;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private int mEnforced;
    @GuardedBy("mLock")
    private Boolean mSwitchOn;
    @GuardedBy("mLock")
    private boolean mMicApplied;
    @GuardedBy("mLock")
    private String mPolicy;

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
            refreshEnforcedLocked();
        }
        mPlatform.watch(this::mirrorPolicy, this::onUserSwitched);
    }

    /** The switch moved (or was read first). {@code on}: Moments on. Handler thread. */
    void onSwitchChanged(boolean on) {
        if (!mEnabled) {
            return;
        }
        synchronized (mLock) {
            mSwitchOn = on;
            // A client module can register after boot; the mask only grows.
            refreshEnforcedLocked();
            applyLocked();
        }
    }

    @GuardedBy("mLock")
    private void refreshEnforcedLocked() {
        int enforced = mPlatform.readEnforced();
        if (enforced < 0) {
            Slog.w(TAG, "Cannot read what the kernel enforces");
            enforced = 0;
        }
        if (enforced != mEnforced) {
            Slog.i(TAG, "Kernel blocks " + maskToString(enforced) + " while the switch is on");
        }
        mEnforced = enforced;
        mPlatform.setProperty(ENFORCED_PROPERTY, Integer.toString(enforced));
    }

    @GuardedBy("mLock")
    private void applyLocked() {
        boolean blocked = Boolean.TRUE.equals(mSwitchOn) && (mEnforced & KERNEL_MIC) != 0;
        if (blocked == mMicApplied) {
            return;
        }
        // Turning the hardware toggle off also turns the software one off, so only do it after
        // turning it on.
        for (int userId : mPlatform.userIds()) {
            mPlatform.setPhysicalMicToggle(userId, blocked);
        }
        mMicApplied = blocked;
    }

    private void onUserSwitched(int userId) {
        synchronized (mLock) {
            if (mMicApplied) {
                mPlatform.setPhysicalMicToggle(userId, true);
            }
        }
    }

    // The system user's choice; the kernel floor is device-wide.
    private void mirrorPolicy() {
        int action = mPlatform.momentsAction();
        String policy = action == Settings.Secure.MOMENTS_ACTION_SENSORS_OFF
                ? Integer.toString(KERNEL_MIC | KERNEL_CAMERA) : "0";
        synchronized (mLock) {
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
            pw.println("switch on: " + mSwitchOn);
            pw.println("hardware mic toggle set: " + mMicApplied);
            pw.println("policy for next boot: " + mPolicy);
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
        public int readEnforced() {
            try {
                String text = Files.readString(Path.of(mPath), StandardCharsets.US_ASCII).trim();
                return Integer.parseInt(text);
            } catch (IOException | NumberFormatException | SecurityException e) {
                return -1;
            }
        }

        @Override
        public int[] userIds() {
            return LocalServices.getService(UserManagerInternal.class).getUserIds();
        }

        @Override
        public void setPhysicalMicToggle(int userId, boolean blocked) {
            LocalServices.getService(SensorPrivacyManagerInternal.class)
                    .setPhysicalToggleSensorPrivacy(userId, Sensors.MICROPHONE, blocked);
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
