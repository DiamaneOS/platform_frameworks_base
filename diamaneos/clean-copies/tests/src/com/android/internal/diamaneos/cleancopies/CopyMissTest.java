/* Copyright (C) 2026 DiamaneOS */
package com.android.internal.diamaneos.cleancopies;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class CopyMissTest {
    private static final String SYSTEMUI = "com.android.systemui";

    // A PNG of the chunks named, each with an empty body.
    private static byte[] png(String... chunks) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        for (String chunk : chunks) {
            out.writeBytes(new byte[] {0, 0, 0, 0});
            out.writeBytes(chunk.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            out.writeBytes(new byte[] {0, 0, 0, 0});
        }
        return out.toByteArray();
    }

    // What a miss did.
    private static final class Seen implements CopyMiss.Miss<String> {
        final List<String> did = new ArrayList<>();

        @Override
        public void keep(String decoded) {
            did.add("keep " + decoded);
        }

        @Override
        public void askCopy() {
            did.add("copy");
        }

        @Override
        public void retryLater() {
            did.add("retry");
        }
    }

    // A decoder: "big" results are 10 bytes, others 1. Null for a file of "no" (the VM busy).
    private static final CopyMiss.Decoder<String> DECODER = new CopyMiss.Decoder<String>() {
        @Override
        public String decode(byte[] bytes) {
            final String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
            return text.equals("no") ? null : text.equals("big") ? "big" : "image";
        }

        @Override
        public long bytes(String decoded) {
            return decoded.equals("big") ? 10 : 1;
        }
    };

    private static CopyMiss.Apk apk(byte[] bytes) {
        return (apkPath, file, max) -> bytes == null || bytes.length > max ? null : new CopyMiss.Input(bytes, () -> {});
    }

    private static String miss(String process, CopyMiss.Apk apk, Seen seen) {
        return CopyMiss.miss(process, false, "/data/app/a/base.apk", "res/drawable/x.png", apk, DECODER, 5, seen);
    }

    @Test
    public void systemUiDecodesAtOnceAndKeepsSmallResults() {
        final Seen seen = new Seen();
        assertEquals("image", miss(SYSTEMUI, apk("png".getBytes()), seen));
        assertEquals(List.of("keep image"), seen.did);
    }

    @Test
    public void aLargeResultIsShownButNotKept() {
        final Seen seen = new Seen();
        assertEquals("big", miss(SYSTEMUI, apk("big".getBytes()), seen));
        assertTrue(seen.did.isEmpty());
    }

    @Test
    public void aDecodeThatGaveNothingAsksForNoCopy() {
        final Seen seen = new Seen();
        assertNull(miss(SYSTEMUI, apk("no".getBytes()), seen));
        assertEquals(List.of("retry"), seen.did);
    }

    @Test
    public void aFileLargerThanTheLimitTakesACopy() {
        final Seen seen = new Seen();
        assertNull(miss(SYSTEMUI, apk(new byte[CopyMiss.DIRECT_MAX_BYTES + 1]), seen));
        assertEquals(List.of("copy"), seen.did);
    }

    @Test
    public void anUnreadableFileTakesACopy() {
        final Seen seen = new Seen();
        assertNull(miss(SYSTEMUI, (apkPath, file, max) -> {
            throw new IOException("gone");
        }, seen));
        assertEquals(List.of("copy"), seen.did);
    }

    @Test
    public void aNinePatchByContentTakesACopyWhateverItsName() {
        final Seen seen = new Seen();
        assertNull(miss(SYSTEMUI, apk(png("IHDR", "npTc", "IDAT", "IEND")), seen));
        assertEquals(List.of("copy"), seen.did);
        assertTrue(CopyMiss.isNinePatch(png("IHDR", "npLb", "npTc", "IDAT")));
        assertFalse(CopyMiss.isNinePatch(png("IHDR", "IDAT", "npTc")));
        assertFalse(CopyMiss.isNinePatch(png("IHDR", "IDAT", "IEND")));
        assertFalse(CopyMiss.isNinePatch("RIFF....WEBP".getBytes()));
        assertFalse(CopyMiss.isNinePatch(new byte[3]));
    }

    @Test
    public void aPlainPngNamedLikeANinePatchIsDecodedAtOnce() {
        final Seen seen = new Seen();
        final byte[] plain = png("IHDR", "IDAT", "IEND");
        assertEquals("image", CopyMiss.miss(SYSTEMUI, false, "/data/app/a/base.apk", "res/drawable/frame.9.png", apk(plain),
                DECODER, 5, seen));
        assertEquals(List.of("keep image"), seen.did);
    }

    @Test
    public void otherProcessesAskForCopies() {
        for (String process : new String[] {"com.android.launcher3", "com.android.settings", null}) {
            final Seen seen = new Seen();
            assertNull(miss(process, apk("png".getBytes()), seen));
            assertEquals(List.of("copy"), seen.did);
        }
        assertTrue(CopyMiss.waits("com.android.launcher3"));
        assertFalse(CopyMiss.waits(SYSTEMUI));
        assertTrue(CopyMiss.decodesNow(SYSTEMUI, false));
        assertFalse(CopyMiss.decodesNow("com.android.launcher3", false));
    }

    @Test
    public void aTruncatedChunkListIsNoNinePatch() {
        final byte[] full = png("IHDR", "npTc");
        final byte[] cut = java.util.Arrays.copyOf(full, full.length - 6);
        assertArrayEquals(java.util.Arrays.copyOf(full, 8), java.util.Arrays.copyOf(cut, 8));
        assertFalse(CopyMiss.isNinePatch(java.util.Arrays.copyOf(full, 8 + 12 + 4)));
    }
    @Test
    public void launcherIconLoadsDecodeAtOnceOnlyWhenEligible() {
        final Seen seen = new Seen();
        assertEquals("image", CopyMiss.miss("com.android.launcher3", true, "/data/app/a/base.apk", "icon.png",
                apk("png".getBytes()), DECODER, 5, seen));
        assertEquals(List.of("keep image"), seen.did);
        final Seen patch = new Seen();
        assertEquals("image", CopyMiss.miss("com.android.launcher3", true, "apk", "icon.png",
                apk(png("IHDR", "npTc", "IDAT")), DECODER, 5, patch));
        assertFalse(CopyMiss.decodesNow("com.android.settings", true));
        assertFalse(CopyMiss.decodesNow("com.android.launcher3", false));
    }

    @Test
    public void ordinaryLargeInputAndItsChargeStayOwnedThroughTheDecode() {
        final boolean[] held = {true};
        final byte[] raster = new byte[1024 * 1024 + 1];
        final CopyMiss.Apk owned = (a, f, max) -> new CopyMiss.Input(raster, () -> held[0] = false);
        final CopyMiss.Decoder<String> decoder = new CopyMiss.Decoder<String>() {
            @Override public String decode(byte[] bytes) {
                assertTrue(held[0]);
                assertTrue(bytes == raster);
                return "image";
            }
            @Override public long bytes(String decoded) { return 1; }
        };
        assertEquals("image", CopyMiss.direct("apk", "icon", owned, decoder, 5, true).decoded);
        assertFalse(held[0]);
        held[0] = true;
        assertNull(CopyMiss.direct("apk", "icon", owned, new CopyMiss.Decoder<String>() {
            @Override public String decode(byte[] bytes) { assertTrue(held[0]); return null; }
            @Override public long bytes(String decoded) { return 1; }
        }, 5, true).decoded);
        assertFalse(held[0]);
        held[0] = true;
        try {
            CopyMiss.direct("apk", "icon", owned, new CopyMiss.Decoder<String>() {
                @Override public String decode(byte[] bytes) { throw new IllegalStateException("listener"); }
                @Override public long bytes(String decoded) { return 1; }
            }, 5, true);
            org.junit.Assert.fail("expected decoder exception");
        } catch (IllegalStateException expected) {
            assertFalse(held[0]);
        }
    }

}
