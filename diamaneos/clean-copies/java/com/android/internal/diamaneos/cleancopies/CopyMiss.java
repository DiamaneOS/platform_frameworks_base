/* Copyright (C) 2026 DiamaneOS */
package com.android.internal.diamaneos.cleancopies;

import java.io.IOException;

/** What a process gate does with an image file that has no clean copy yet. */
final class CopyMiss {
    static final String LAUNCHER = "com.android.launcher3";
    static final String SYSTEMUI = "com.android.systemui";
    /** SystemUI's existing direct-input ceiling. */
    static final int DIRECT_MAX_BYTES = 1024 * 1024;
    /** The current gate input ceiling for guarded background Launcher icon loads. */
    static final int LAUNCHER_MAX_BYTES = 256 * 1024 * 1024;

    /** What became of a file decoded at once. */
    enum Way {
        /** Decoded: shown now. */
        DECODED,
        /** Not for a decode at once (too large or unreadable): a copy is asked for, as elsewhere. */
        COPY,
        /** The decode gave nothing (the VM busy or the file refused): no copy, the gate's refill loads it again. */
        NONE,
    }

    /** The file's bytes from its APK, or null when it is larger than {@code max}. */
    interface Apk {
        Input read(String apkPath, String file, int max) throws IOException;
    }

    /** A private APK input whose memory remains charged until its overlapping VM decode finishes. */
    static final class Input implements AutoCloseable {
        final byte[] bytes;
        private Runnable mRelease;

        Input(byte[] bytes, Runnable release) {
            this.bytes = bytes;
            mRelease = release;
        }

        @Override
        public void close() {
            final Runnable release = mRelease;
            mRelease = null;
            if (release != null) release.run();
        }
    }

    /** The decode through the gate (in the VM), null when it gave nothing, and the size the result holds. */
    interface Decoder<B> {
        B decode(byte[] bytes);

        long bytes(B decoded);
    }

    static final class Direct<B> {
        final Way way;
        final B decoded;
        final boolean cacheable;

        Direct(Way way, B decoded, boolean cacheable) {
            this.way = way;
            this.decoded = decoded;
            this.cacheable = cacheable;
        }
    }

    private CopyMiss() {}

    /**
     * True when a file without a copy is decoded at once in the VM, in the process's own lane, instead of waiting for
     * a copy. SystemUI's notification images show at once on stock, and a copy is a VM run of its own that would hold
     * up the next images.
     */
    static boolean decodesNow(String processName, boolean launcherIcon) {
        return SYSTEMUI.equals(processName) || (LAUNCHER.equals(processName) && launcherIcon);
    }

    /** True when an icon load waits (off the main thread) for the copy it asked for: the launcher's. */
    static boolean waits(String processName) {
        return LAUNCHER.equals(processName);
    }

    /**
     * The decode at once of {@code file}. SystemUI keeps its existing small raster route. Guarded background
     * Launcher icons also admit larger inputs and nine-patches, whose metadata the decoder checks and rebuilds.
     */
    static <B> Direct<B> direct(String apkPath, String file, Apk apk, Decoder<B> decoder, long cacheMax,
            boolean launcherIcon) {
        if (apkPath == null || file == null) {
            return new Direct<>(Way.COPY, null, false);
        }
        final Input input;
        try {
            input = apk.read(apkPath, file, launcherIcon ? LAUNCHER_MAX_BYTES : DIRECT_MAX_BYTES);
        } catch (IOException | RuntimeException e) {
            return new Direct<>(Way.COPY, null, false);
        }
        try (Input owned = input) {
            if (owned == null || owned.bytes.length == 0 || (!launcherIcon && isNinePatch(owned.bytes))) {
                return new Direct<>(Way.COPY, null, false);
            }
            final B decoded = decoder.decode(owned.bytes);
            if (decoded == null) {
                return new Direct<>(Way.NONE, null, false);
            }
            return new Direct<>(Way.DECODED, decoded, decoder.bytes(decoded) <= cacheMax);
        }
    }

    /** What a miss does besides its result. */
    interface Miss<B> {
        /** Keeps a decoded result for the next load. */
        void keep(B decoded);

        /** Asks for the file's copy. */
        void askCopy();

        /** Has what waits for the app's images load again in a while. */
        void retryLater();
    }

    /**
     * A file without a copy: in SystemUI or a guarded Launcher icon load decoded at once, kept when small enough,
     * else a copy asked
     * for when the file is not for a decode at once, and nothing (a retry later, no copy) when the decode gave nothing.
     * In any other process a copy is asked for. The result, or null.
     */
    static <B> B miss(String processName, boolean launcherIcon, String apkPath, String file, Apk apk,
            Decoder<B> decoder, long cacheMax, Miss<B> to) {
        if (!decodesNow(processName, launcherIcon)) {
            to.askCopy();
            return null;
        }
        final Direct<B> direct = direct(apkPath, file, apk, decoder, cacheMax,
                LAUNCHER.equals(processName) && launcherIcon);
        switch (direct.way) {
            case DECODED:
                if (direct.cacheable) {
                    to.keep(direct.decoded);
                }
                return direct.decoded;
            case COPY:
                to.askCopy();
                return null;
            default:
                to.retryLater();
                return null;
        }
    }

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    /** True for a PNG with a nine-patch chunk (npTc) before its image data, whatever its file name. */
    static boolean isNinePatch(byte[] bytes) {
        if (bytes.length < PNG.length) {
            return false;
        }
        for (int i = 0; i < PNG.length; i++) {
            if (bytes[i] != PNG[i]) {
                return false;
            }
        }
        int at = PNG.length;
        while (at + 8 <= bytes.length) {
            final long length = ((bytes[at] & 0xffL) << 24) | ((bytes[at + 1] & 0xff) << 16)
                    | ((bytes[at + 2] & 0xff) << 8) | (bytes[at + 3] & 0xff);
            final String type = new String(bytes, at + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if ("npTc".equals(type)) {
                return true;
            }
            if ("IDAT".equals(type) || "IEND".equals(type)) {
                return false;
            }
            final long next = at + 12L + length;
            if (next > bytes.length) {
                return false;
            }
            at = (int) next;
        }
        return false;
    }
}
