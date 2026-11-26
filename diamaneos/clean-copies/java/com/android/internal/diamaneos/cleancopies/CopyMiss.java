/* Copyright (C) 2026 DiamaneOS */
package com.android.internal.diamaneos.cleancopies;

/** What a process gate does with an image file that has no clean copy yet. */
final class CopyMiss {
    static final String LAUNCHER = "com.android.launcher3";
    static final String SYSTEMUI = "com.android.systemui";

    private CopyMiss() {}

    /**
     * True when the file is decoded at once in the VM, in the process's own lane, instead of waiting for a copy.
     * SystemUI's notification images show at once on stock, and a copy is a VM run of its own that would hold up the
     * next images. Nine-patch files keep their copies, which carry the chunk.
     */
    static boolean decodesNow(String processName, String file) {
        return SYSTEMUI.equals(processName) && file != null && !file.endsWith(".9.png");
    }

    /** True when an icon load waits (off the main thread) for the copy it asked for: the launcher's. */
    static boolean waits(String processName) {
        return LAUNCHER.equals(processName);
    }
}
