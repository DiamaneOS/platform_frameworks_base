/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 The DiamaneOS Project
 */
package android.ext.settings.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.GosPackageState;
import android.content.pm.GosPackageStateFlag;
import android.os.SystemProperties;

/**
 * Hardware video decoding for one app, on devices that offer "Selected apps" in Settings >
 * Security & privacy > Exploit protection > Hardware video decoding. Off by default. The user
 * turns it on per app while that choice is Selected apps. Never and All video decide it for every
 * app, so it cannot be changed then.
 *
 * <p>The device's codec service creates a hardware decoder only for the UIDs of the apps selected
 * here. system_server passes the selection on (com.android.server.ext.HwVideoDecodeApps).
 *
 * @hide
 */
public class AswUseHardwareVideoDecoding extends AppSwitch {
    public static final AswUseHardwareVideoDecoding I = new AswUseHardwareVideoDecoding();

    /** The user's choice for the whole device, which Settings stores. */
    public static final String MODE_PROPERTY = "persist.diamaneos.hw_video_decode";
    public static final int MODE_NEVER = 0;
    public static final int MODE_ALL_VIDEO = 1;
    public static final int MODE_SELECTED_APPS = 3;

    private AswUseHardwareVideoDecoding() {
        gosPsFlagNonDefault = GosPackageStateFlag.USE_HARDWARE_VIDEO_DECODING_NON_DEFAULT;
        gosPsFlag = GosPackageStateFlag.USE_HARDWARE_VIDEO_DECODING;
    }

    /** Whether the user selected this app. Only an explicit "on" counts. */
    public static boolean isSelected(GosPackageState ps) {
        return ps.hasFlag(GosPackageStateFlag.USE_HARDWARE_VIDEO_DECODING_NON_DEFAULT)
                && ps.hasFlag(GosPackageStateFlag.USE_HARDWARE_VIDEO_DECODING);
    }

    /** The user's choice for the whole device. Unset or unknown is Never. */
    public static int getMode() {
        int mode = SystemProperties.getInt(MODE_PROPERTY, MODE_NEVER);
        return switch (mode) {
            case MODE_ALL_VIDEO, MODE_SELECTED_APPS -> mode;
            default -> MODE_NEVER;
        };
    }

    @Override
    public Boolean getImmutableValue(Context ctx, int userId, ApplicationInfo appInfo,
                                     GosPackageState ps, StateInfo si) {
        int mode = getMode();
        if (mode == MODE_SELECTED_APPS) {
            return null;
        }
        return mode == MODE_ALL_VIDEO;
    }

    @Override
    protected boolean getDefaultValueInner(Context ctx, int userId, ApplicationInfo appInfo,
                                           GosPackageState ps, StateInfo si) {
        return false;
    }
}
