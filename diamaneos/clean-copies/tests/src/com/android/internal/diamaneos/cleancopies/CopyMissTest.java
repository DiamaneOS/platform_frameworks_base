/* Copyright (C) 2026 DiamaneOS */
package com.android.internal.diamaneos.cleancopies;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CopyMissTest {
    @Test
    public void systemUiDecodesNotificationImagesAtOnce() {
        assertTrue(CopyMiss.decodesNow("com.android.systemui", "res/drawable-xxhdpi-v4/stat_notify.png"));
        assertTrue(CopyMiss.decodesNow("com.android.systemui", "res/mipmap-anydpi/ic_large.webp"));
        assertFalse(CopyMiss.waits("com.android.systemui"));
    }

    @Test
    public void nineInSystemUiKeepsItsCopy() {
        assertFalse(CopyMiss.decodesNow("com.android.systemui", "res/drawable/frame.9.png"));
    }

    @Test
    public void launcherWaitsForItsCopies() {
        assertFalse(CopyMiss.decodesNow("com.android.launcher3", "res/mipmap-xxhdpi/ic_launcher.png"));
        assertTrue(CopyMiss.waits("com.android.launcher3"));
    }

    @Test
    public void otherProcessesTakeCopies() {
        assertFalse(CopyMiss.decodesNow("com.android.settings", "res/drawable/a.png"));
        assertFalse(CopyMiss.decodesNow(null, "res/drawable/a.png"));
        assertFalse(CopyMiss.waits("com.android.settings"));
    }
}
