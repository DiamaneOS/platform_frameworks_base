/* Copyright (C) 2026 DiamaneOS */
package com.android.internal.diamaneos.cleancopies;

import java.io.IOException;

/** What a process gate does with an image file that has no clean copy yet. */
final class CopyMiss {
    static final String LAUNCHER = "com.android.launcher3";
    static final String SYSTEMUI = "com.android.systemui";
    /** The largest file decoded at once: larger ones take a copy, which is made in a box of the display's size. */
    static final int DIRECT_MAX_BYTES = 1024 * 1024;

    /** What became of a file decoded at once. */
    enum Way {
        /** Decoded: shown now. */
        DECODED,
        /** Not for a decode at once (too large, a nine-patch, unreadable): a copy is asked for, as elsewhere. */
        COPY,
        /** The decode gave nothing (the VM busy or the file refused): no copy, the gate's refill loads it again. */
        NONE,
    }

    /** The file's bytes from its APK, or null when it is larger than {@code max}. */
    interface Apk {
        byte[] read(String apkPath, String file, int max) throws IOException;
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
    static boolean decodesNow(String processName) {
        return SYSTEMUI.equals(processName);
    }

    /** True when an icon load waits (off the main thread) for the copy it asked for: the launcher's. */
    static boolean waits(String processName) {
        return LAUNCHER.equals(processName);
    }

    /**
     * The decode at once of {@code file}: files up to {@link #DIRECT_MAX_BYTES} that are not nine-patches (by their
     * content, whatever the name: a copy carries the chunk). A result up to {@code cacheMax} bytes may be kept.
     */
    static <B> Direct<B> direct(String apkPath, String file, Apk apk, Decoder<B> decoder, long cacheMax) {
        if (apkPath == null || file == null) {
            return new Direct<>(Way.COPY, null, false);
        }
        final byte[] bytes;
        try {
            bytes = apk.read(apkPath, file, DIRECT_MAX_BYTES);
        } catch (IOException | RuntimeException e) {
            return new Direct<>(Way.COPY, null, false);
        }
        if (bytes == null || bytes.length == 0 || isNinePatch(bytes)) {
            return new Direct<>(Way.COPY, null, false);
        }
        final B decoded = decoder.decode(bytes);
        if (decoded == null) {
            return new Direct<>(Way.NONE, null, false);
        }
        return new Direct<>(Way.DECODED, decoded, decoder.bytes(decoded) <= cacheMax);
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
     * A file without a copy: in SystemUI decoded at once ({@link #direct}), kept when small enough, else a copy asked
     * for when the file is not for a decode at once, and nothing (a retry later, no copy) when the decode gave nothing.
     * In any other process a copy is asked for. The result, or null.
     */
    static <B> B miss(String processName, String apkPath, String file, Apk apk, Decoder<B> decoder, long cacheMax,
            Miss<B> to) {
        if (!decodesNow(processName)) {
            to.askCopy();
            return null;
        }
        final Direct<B> direct = direct(apkPath, file, apk, decoder, cacheMax);
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
