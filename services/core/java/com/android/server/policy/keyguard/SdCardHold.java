package com.android.server.policy.keyguard;

import android.os.SystemProperties;
import android.util.Slog;

/**
 * DiamaneOS: a new SD card waits while the phone is locked.
 *
 * <p>The kernel parses a card's registers and replies in its MMC core, and the card slot's
 * controller cannot be handed to a VM. While kernel.hold_new_sd is 1, the kernel leaves a card
 * that appears on a removable MMC host unpowered and sends it no command. Clearing the hold starts
 * a waiting card as if just inserted. A card that already works keeps working (kernel_common
 * drivers/mmc/core/sd_hold.c).
 *
 * <p>The hold follows the lock screen, as the USB-C port control does: set while the lock screen
 * shows, cleared when it is dismissed, whatever the USB-C port setting. init writes
 * security.hold_new_sd to the kernel (vendor_diamaneos sdhold/hold_new_sd.rc). The kernel starts
 * with the hold set, and the system server sets it again when it starts, before the lock screen
 * state is known.
 */
final class SdCardHold {
    private static final String TAG = "SdCardHold";
    private static final String PROP = "security.hold_new_sd";

    private SdCardHold() {}

    static void onKeyguardStateUnknown() {
        set(true);
    }

    static void onKeyguardShowingStateChanged(boolean showing) {
        set(showing);
    }

    private static synchronized void set(boolean hold) {
        String value = hold ? "1" : "0";
        try {
            SystemProperties.set(PROP, value);
            Slog.d(TAG, "set " + PROP + " to " + value);
        } catch (RuntimeException e) {
            Slog.e(TAG, "unable to set " + PROP + " to " + value, e);
        }
    }
}
