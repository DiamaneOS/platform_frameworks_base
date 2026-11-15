/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 The DiamaneOS Project
 */
package com.android.server.ext;

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.GosPackageState;
import android.ext.settings.app.AswUseHardwareVideoDecoding;
import android.os.Process;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.server.pm.Computer;
import com.android.server.pm.PackageManagerLocal;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.PackageUserStateInternal;

/**
 * Hardware video decoding for selected apps. Turns the per-app switch
 * (AswUseHardwareVideoDecoding) into one system property per selected app UID,
 * {@code diamaneos.hw_video_decode.uid.<uid>} = "1". The device's codec service creates a hardware
 * decoder only for a binder caller whose property is "1", and mediaserver lists the hardware
 * decoders only for those callers.
 *
 * <p>Only system_server may set these properties and apps cannot read them (device sepolicy). They
 * are not persistent: this class sets them at every boot, before apps start, and follows every
 * change of the switch, install, uninstall and user removal. Only an explicit selection writes
 * "1". A UID that was never selected this boot gets no property, which reads as not selected.
 */
final class HwVideoDecodeApps {
    private static final String TAG = "HwVideoDecodeApps";
    private static final String PROPERTY_PREFIX = "diamaneos.hw_video_decode.uid.";

    private final SystemServerExt sse;
    // The UIDs whose property is "1". Guarded by "this".
    private final ArraySet<Integer> selectedUids = new ArraySet<>();

    private HwVideoDecodeApps(SystemServerExt sse) {
        this.sse = sse;
    }

    /** Sets the properties of every user's selected apps, then follows changes. */
    static void init(SystemServerExt sse) {
        var apps = new HwVideoDecodeApps(sse);
        // Listen first, so a change during the first pass is not lost.
        apps.registerListeners();
        apps.setAll();
    }

    private void registerListeners() {
        sse.packageManager.gosPackageStatePmHooks.addChangeCallback(
                new PackageManagerLocal.GosPackageStateChangeCallback(sse.bgHandler) {
                    @Override
                    public void onGosPackageStateChanged(int uid, @NonNull GosPackageState state,
                                                         int userId) {
                        synchronized (HwVideoDecodeApps.this) {
                            if (AswUseHardwareVideoDecoding.isSelected(state)
                                    || selectedUids.contains(uid)) {
                                update(uid);
                            }
                        }
                    }
                });

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onBroadcast(intent);
            }
        };
        // An archived app keeps its switch, and gets it back when it is installed again.
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addDataScheme("package");
        sse.context.registerReceiverForAllUsers(receiver, packages, null, sse.bgHandler);

        IntentFilter removals = new IntentFilter();
        removals.addAction(Intent.ACTION_UID_REMOVED);
        removals.addAction(Intent.ACTION_USER_REMOVED);
        sse.context.registerReceiverForAllUsers(receiver, removals, null, sse.bgHandler);
    }

    private synchronized void onBroadcast(Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }
        switch (action) {
            case Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REMOVED -> {
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    // An update keeps the switch.
                    return;
                }
                int uid = intent.getIntExtra(Intent.EXTRA_UID, Process.INVALID_UID);
                if (uid != Process.INVALID_UID) {
                    update(uid);
                }
            }
            case Intent.ACTION_UID_REMOVED -> {
                int uid = intent.getIntExtra(Intent.EXTRA_UID, Process.INVALID_UID);
                if (uid != Process.INVALID_UID) {
                    set(uid, false);
                }
            }
            case Intent.ACTION_USER_REMOVED -> {
                int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
                if (userId == UserHandle.USER_NULL) {
                    return;
                }
                for (int i = selectedUids.size() - 1; i >= 0; --i) {
                    int uid = selectedUids.valueAt(i);
                    if (UserHandle.getUserId(uid) == userId) {
                        set(uid, false);
                    }
                }
            }
            default -> {}
        }
    }

    private synchronized void setAll() {
        Computer snapshot = sse.packageManager.snapshotComputer();
        for (PackageStateInternal ps : snapshot.getPackageStates().values()) {
            int appId = ps.getAppId();
            if (!Process.isApplicationUid(appId)) {
                continue;
            }
            SparseArray<? extends PackageUserStateInternal> userStates = ps.getUserStates();
            for (int i = 0; i < userStates.size(); ++i) {
                if (isSelected(userStates.valueAt(i))) {
                    set(UserHandle.getUid(userStates.keyAt(i), appId), true);
                }
            }
        }
        Slog.i(TAG, selectedUids.size() + " app UIDs selected");
    }

    // Reads the switch of every package with this UID again.
    private void update(int uid) {
        set(uid, isSelected(uid));
    }

    private boolean isSelected(int uid) {
        if (!Process.isApplicationUid(uid)) {
            return false;
        }
        int appId = UserHandle.getAppId(uid);
        int userId = UserHandle.getUserId(uid);
        Computer snapshot = sse.packageManager.snapshotComputer();
        for (PackageStateInternal ps : snapshot.getPackageStates().values()) {
            if (ps.getAppId() == appId && isSelected(ps.getUserStates().get(userId))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSelected(PackageUserStateInternal userState) {
        return userState != null && userState.isInstalled()
                && AswUseHardwareVideoDecoding.isSelected(userState.getGosPackageState());
    }

    private void set(int uid, boolean selected) {
        if (selected == selectedUids.contains(uid)) {
            return;
        }
        if (selected && !Process.isApplicationUid(uid)) {
            return;
        }
        try {
            SystemProperties.set(PROPERTY_PREFIX + uid, selected ? "1" : "0");
        } catch (RuntimeException e) {
            // The device has no such property (no sepolicy for it), or the property service
            // refused. A "1" that cannot be cleared stays until the next boot.
            Slog.e(TAG, "cannot set the property of uid " + uid, e);
            return;
        }
        if (selected) {
            selectedUids.add(uid);
        } else {
            selectedUids.remove(uid);
        }
        Slog.i(TAG, "uid " + uid + (selected ? " selected" : " no longer selected"));
    }
}
