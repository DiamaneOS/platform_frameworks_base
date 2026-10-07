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

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.input.IMomentsSwitchListener;
import android.hardware.input.InputManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArray;
import android.view.InputDevice;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Reports the Moments switch, a built-in slider that sends one EV_SW code, to SystemUI.
 *
 * <p>The code comes from {@code config_momentsSwitchCode}; -1 (the default) turns all of this
 * off. Only one internal input device counts: the first one found that reports the code. The
 * kernel's switch reports carry no device, so each report only prompts a fresh read of that
 * device, and listeners hear about real changes only. A USB, Bluetooth or later-added device
 * that sends the same code cannot move the switch.
 *
 * <p>Every binder entry point requires {@link #PERMISSION}, a signature permission that only
 * SystemUI asks for.
 */
final class MomentsSwitchController {
    private static final String TAG = "MomentsSwitch";

    static final String PERMISSION = "de.diamaneos.permission.MOMENTS_SWITCH";

    private static final int NO_DEVICE = Integer.MIN_VALUE;

    private final Context mContext;
    private final NativeInputManagerService mNative;
    private final Handler mHandler;
    private final Supplier<InputDevice[]> mInputDevices;
    private final int mCode;
    private final int mActiveValue;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private int mDeviceId = NO_DEVICE;
    @GuardedBy("mLock")
    private int mState = InputManager.SWITCH_STATE_UNKNOWN;
    @GuardedBy("mLock")
    private final SparseArray<ListenerRecord> mListeners = new SparseArray<>();
    @GuardedBy("mLock")
    private int mChangesSent;
    @GuardedBy("mLock")
    private int mReportsDropped;
    // In system_server, on the handler thread: hears every real change, as listeners do.
    private Consumer<Boolean> mInternalListener;

    MomentsSwitchController(Context context, NativeInputManagerService nativeService,
            Looper looper, Supplier<InputDevice[]> inputDevices) {
        this(context, nativeService, looper, inputDevices,
                context.getResources().getInteger(R.integer.config_momentsSwitchCode),
                context.getResources().getInteger(R.integer.config_momentsSwitchActiveValue));
    }

    @VisibleForTesting
    MomentsSwitchController(Context context, NativeInputManagerService nativeService,
            Looper looper, Supplier<InputDevice[]> inputDevices, int code, int activeValue) {
        mContext = context;
        mNative = nativeService;
        mHandler = new Handler(looper);
        mInputDevices = inputDevices;
        // Linux has 16 switch codes (SW_MAX 0x10); the reader keeps codes below 32.
        mCode = (code >= 0 && code < 32) ? code : -1;
        mActiveValue = activeValue == 0 ? 0 : 1;
    }

    boolean isEnabled() {
        return mCode >= 0;
    }

    /** Sets the in-process listener (the kernel floor). Before {@link #systemRunning()}. */
    void setInternalListener(Consumer<Boolean> listener) {
        mInternalListener = listener;
    }

    /** Finds the switch and reads its first state, once the input reader is running. */
    void systemRunning() {
        if (!isEnabled()) {
            return;
        }
        mHandler.post(() -> refresh(0 /* whenNanos */));
    }

    /** Called with every switch report from the input reader, on any thread. */
    void notifySwitch(long whenNanos, int switchMask) {
        if (!isEnabled() || (switchMask & (1 << mCode)) == 0) {
            return;
        }
        mHandler.post(() -> refresh(whenNanos));
    }

    /** Called after the set of input devices changed. */
    void onInputDevicesChanged() {
        if (!isEnabled()) {
            return;
        }
        mHandler.post(() -> refresh(0 /* whenNanos */));
    }

    // Binder call
    int getState() {
        enforcePermission("getMomentsSwitchState()");
        if (!isEnabled()) {
            return InputManager.SWITCH_STATE_UNKNOWN;
        }
        synchronized (mLock) {
            return readStateLocked();
        }
    }

    // Binder call
    void registerListener(@NonNull IMomentsSwitchListener listener) {
        enforcePermission("registerMomentsSwitchListener()");
        Objects.requireNonNull(listener, "listener must not be null");
        if (!isEnabled()) {
            return;
        }
        final int pid = Binder.getCallingPid();
        final ListenerRecord record = new ListenerRecord(pid, listener);
        try {
            listener.asBinder().linkToDeath(record, 0);
        } catch (RemoteException e) {
            return;
        }
        final int state;
        synchronized (mLock) {
            ListenerRecord old = mListeners.get(pid);
            if (old != null) {
                old.unlink();
            }
            mListeners.put(pid, record);
            state = readStateLocked();
        }
        if (state != InputManager.SWITCH_STATE_UNKNOWN) {
            record.notify(0 /* whenNanos */, state == InputManager.SWITCH_STATE_ON);
        }
    }

    // Binder call
    void unregisterListener(@NonNull IMomentsSwitchListener listener) {
        enforcePermission("unregisterMomentsSwitchListener()");
        Objects.requireNonNull(listener, "listener must not be null");
        final int pid = Binder.getCallingPid();
        synchronized (mLock) {
            ListenerRecord record = mListeners.get(pid);
            if (record != null && record.mListener.asBinder() == listener.asBinder()) {
                record.unlink();
                mListeners.remove(pid);
            }
        }
    }

    private void enforcePermission(String method) {
        mContext.enforceCallingOrSelfPermission(PERMISSION, "Moments switch: " + method);
    }

    // Handler thread. Re-reads the pinned device and tells listeners if the position changed.
    private void refresh(long whenNanos) {
        final List<ListenerRecord> toNotify;
        final boolean on;
        synchronized (mLock) {
            int state = readStateLocked();
            if (state == InputManager.SWITCH_STATE_UNKNOWN || state == mState) {
                if (whenNanos != 0) {
                    mReportsDropped++;
                }
                if (state == InputManager.SWITCH_STATE_UNKNOWN) {
                    mState = state;
                }
                return;
            }
            mState = state;
            mChangesSent++;
            on = state == InputManager.SWITCH_STATE_ON;
            toNotify = new ArrayList<>(mListeners.size());
            for (int i = 0; i < mListeners.size(); i++) {
                toNotify.add(mListeners.valueAt(i));
            }
        }
        Slog.i(TAG, "Switch " + (on ? "on" : "off"));
        if (mInternalListener != null) {
            mInternalListener.accept(on);
        }
        for (ListenerRecord record : toNotify) {
            record.notify(whenNanos, on);
        }
    }

    @GuardedBy("mLock")
    private int readStateLocked() {
        pinDeviceLocked();
        if (mDeviceId == NO_DEVICE) {
            return InputManager.SWITCH_STATE_UNKNOWN;
        }
        int raw = mNative.getSwitchState(mDeviceId, InputDevice.SOURCE_ANY, mCode);
        if (raw != InputManager.SWITCH_STATE_ON && raw != InputManager.SWITCH_STATE_OFF) {
            // The device went away; find it again on the next read.
            mDeviceId = NO_DEVICE;
            return InputManager.SWITCH_STATE_UNKNOWN;
        }
        return raw == mActiveValue ? InputManager.SWITCH_STATE_ON
                : InputManager.SWITCH_STATE_OFF;
    }

    // Keeps the pinned device while it exists; otherwise takes the internal device with the
    // lowest id that reports the code, which is the one present since boot.
    @GuardedBy("mLock")
    private void pinDeviceLocked() {
        InputDevice[] devices = mInputDevices.get();
        if (devices == null) {
            devices = new InputDevice[0];
        }
        if (mDeviceId != NO_DEVICE) {
            for (InputDevice device : devices) {
                if (device.getId() == mDeviceId) {
                    return;
                }
            }
            Slog.w(TAG, "Switch device " + mDeviceId + " went away");
            mDeviceId = NO_DEVICE;
        }
        int found = NO_DEVICE;
        for (InputDevice device : devices) {
            int id = device.getId();
            if (id < 0 || device.isExternal() || (found != NO_DEVICE && id > found)) {
                continue;
            }
            if (mNative.getSwitchState(id, InputDevice.SOURCE_ANY, mCode)
                    != InputManager.SWITCH_STATE_UNKNOWN) {
                found = id;
            }
        }
        if (found != NO_DEVICE) {
            mDeviceId = found;
            Slog.i(TAG, "Switch code " + mCode + " is on input device " + found);
        }
    }

    void dump(IndentingPrintWriter pw) {
        pw.println("Moments switch:");
        pw.increaseIndent();
        pw.println("code: " + mCode + (isEnabled() ? "" : " (off)"));
        if (isEnabled()) {
            synchronized (mLock) {
                pw.println("active value: " + mActiveValue);
                pw.println("device id: " + (mDeviceId == NO_DEVICE ? "none" : mDeviceId));
                pw.println("state: " + stateToString(mState));
                pw.println("listeners: " + mListeners.size());
                pw.println("changes sent: " + mChangesSent);
                pw.println("reports dropped: " + mReportsDropped);
            }
        }
        pw.decreaseIndent();
    }

    private static String stateToString(int state) {
        return switch (state) {
            case InputManager.SWITCH_STATE_ON -> "on";
            case InputManager.SWITCH_STATE_OFF -> "off";
            default -> "unknown";
        };
    }

    @VisibleForTesting
    int getPinnedDeviceIdForTesting() {
        synchronized (mLock) {
            return mDeviceId == NO_DEVICE ? -1 : mDeviceId;
        }
    }

    private final class ListenerRecord implements IBinder.DeathRecipient {
        private final int mPid;
        private final IMomentsSwitchListener mListener;

        ListenerRecord(int pid, IMomentsSwitchListener listener) {
            mPid = pid;
            mListener = listener;
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                if (mListeners.get(mPid) == this) {
                    mListeners.remove(mPid);
                }
            }
        }

        void unlink() {
            mListener.asBinder().unlinkToDeath(this, 0);
        }

        void notify(long whenNanos, boolean on) {
            try {
                mListener.onMomentsSwitchChanged(whenNanos, on);
            } catch (RemoteException e) {
                Slog.w(TAG, "Listener in pid " + mPid + " is gone", e);
                binderDied();
            }
        }
    }
}
