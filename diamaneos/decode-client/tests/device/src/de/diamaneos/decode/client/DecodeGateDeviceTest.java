/*
 * Copyright (C) 2026 DiamaneOS
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

package de.diamaneos.decode.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DecodeGate;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Movie;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.diamaneos.cleancopies.VerifiedSystemFiles;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The decode gate (DecodeGate, forced on here as in a gated process): every entry point of BitmapFactory, ImageDecoder,
 * BitmapRegionDecoder, Movie and Drawable.createFrom takes its bytes to the VM and gives what the API gives (sizes,
 * sample sizes, crops, configs, premultiplication, alpha masks, regions, the upright header of a turned JPEG, animated
 * GIFs), the exemption takes only regular files of verified system partitions decided from the open file, the main
 * thread waits within its time, a call not waited for finishes in the background and the same call is answered from
 * the cache, the gate's own threads never go through it again, an ordinary caller (the shell) is refused by the
 * provider, and a process without the gate decodes as before. Also: BitmapFactory and regions give the stored pixels
 * of a mirrored or turned JPEG and ImageDecoder the shown ones, RGB_565 only for opaque images, overlapping requests
 * on one memory file stay apart, assets of an APK outside the system image and the app go through the gate, an answer
 * above half the cache is not kept, the provider's lanes are capped per origin and app and taken in turn, and images
 * above the VM's limit come at full detail. With the instrumentation argument {@code decodeGateStopped=true} (the
 * decode service stopped by the caller), every entry point fails closed within its time.
 */
@RunWith(AndroidJUnit4.class)
public class DecodeGateDeviceTest {
    private static final String TAG = "DecodeGateTest";
    private static final long MARGIN_MS = 150;
    // Left half red, right half blue, 64 x 32: crops and regions show which part they took.
    private static final int W = 64;
    private static final int H = 32;

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Before
    public void gated() {
        DecodeGate.setForcedForTest(true);
        DecodeGate.resetForTest();
        ProbeProvider.ICONS.clear();
    }

    @After
    public void asBefore() {
        DecodeGate.setForcedForTest(null);
        DecodeGate.setWaitForTest(null);
        DecodeGate.resetForTest();
        ProbeProvider.ICONS.clear();
    }

    private static byte[] png() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(redBlue().compress(Bitmap.CompressFormat.PNG, 100, out));
        return out.toByteArray();
    }

    private static Bitmap redBlue() {
        final Bitmap bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final Paint paint = new Paint();
        paint.setColor(Color.RED);
        canvas.drawRect(0, 0, W / 2, H, paint);
        paint.setColor(Color.BLUE);
        canvas.drawRect(W / 2, 0, W, H, paint);
        return bitmap;
    }

    // A one-pixel GIF (GIF89a), the smallest a Movie takes.
    private static final byte[] GIF = {
        'G', 'I', 'F', '8', '9', 'a', 1, 0, 1, 0, (byte) 0x80, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff,
        (byte) 0xff, ',', 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 'D', 1, 0, ';',
    };

    private static void assertRedBlue(Bitmap bitmap, int width, int height) {
        assertNotNull("no bitmap", bitmap);
        assertEquals(width, bitmap.getWidth());
        assertEquals(height, bitmap.getHeight());
        final Bitmap soft = bitmap.getConfig() == Bitmap.Config.HARDWARE
                ? bitmap.copy(Bitmap.Config.ARGB_8888, false) : bitmap;
        assertColor(Color.RED, soft.getPixel(width / 4, height / 2));
        assertColor(Color.BLUE, soft.getPixel(width * 3 / 4, height / 2));
    }

    private static void assertColor(int want, int got) {
        assertTrue(String.format("pixel %08x, want %08x", got, want),
                Math.abs(Color.red(got) - Color.red(want)) <= 8 && Math.abs(Color.green(got) - Color.green(want)) <= 8
                        && Math.abs(Color.blue(got) - Color.blue(want)) <= 8);
    }

    // Decodes and probes the VM answered, and failures, since {@code before}.
    private static int[] since(int[] before) {
        final int[] now = DecodeGate.countsForTest();
        return new int[] {now[0] - before[0], now[1] - before[1], now[2] - before[2]};
    }

    // Answers from the cache since {@code before}.
    private static int hitsSince(int[] before) {
        return DecodeGate.countsForTest()[4] - before[4];
    }

    private File file(byte[] bytes) throws Exception {
        final File file = File.createTempFile("gate", ".png", mContext.getCacheDir());
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
        return file;
    }

    // BitmapFactory

    @Test
    public void bitmapFactoryByteArrayStreamFileAndDescriptor() throws Exception {
        final byte[] png = png();
        int[] before = DecodeGate.countsForTest();
        assertRedBlue(BitmapFactory.decodeByteArray(png, 0, png.length), W, H);
        DecodeGate.resetForTest();
        assertRedBlue(BitmapFactory.decodeStream(new ByteArrayInputStream(png)), W, H);
        DecodeGate.resetForTest();
        final File file = file(png);
        try {
            assertRedBlue(BitmapFactory.decodeFile(file.getPath()), W, H);
            DecodeGate.resetForTest();
            try (FileInputStream in = new FileInputStream(file)) {
                Os.lseek(in.getFD(), 0, OsConstants.SEEK_SET);
                assertRedBlue(BitmapFactory.decodeFileDescriptor(in.getFD()), W, H);
                assertEquals("the descriptor's position is kept", 0,
                        Os.lseek(in.getFD(), 0, OsConstants.SEEK_CUR));
            }
        } finally {
            file.delete();
        }
        assertTrue(Arrays.equals(new int[] {4, 4, 0}, since(before)));
    }

    @Test
    public void bitmapFactoryBoundsAreTheVmProbe() {
        final byte[] png = png();
        final int[] before = DecodeGate.countsForTest();
        final BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        assertNull(BitmapFactory.decodeByteArray(png, 0, png.length, opts));
        assertEquals(W, opts.outWidth);
        assertEquals(H, opts.outHeight);
        assertEquals("image/png", opts.outMimeType);
        assertTrue(Arrays.equals(new int[] {0, 1, 0}, since(before)));
    }

    @Test
    public void bitmapFactoryOptions() {
        final byte[] png = png();
        final BitmapFactory.Options sampled = new BitmapFactory.Options();
        sampled.inSampleSize = 2;
        assertRedBlue(BitmapFactory.decodeByteArray(png, 0, png.length, sampled), W / 2, H / 2);

        final BitmapFactory.Options mutable = new BitmapFactory.Options();
        mutable.inMutable = true;
        mutable.inPreferredConfig = Bitmap.Config.RGB_565;
        final Bitmap m = BitmapFactory.decodeByteArray(png, 0, png.length, mutable);
        assertRedBlue(m, W, H);
        assertTrue(m.isMutable());
        assertEquals(Bitmap.Config.RGB_565, m.getConfig());

        final BitmapFactory.Options hardware = new BitmapFactory.Options();
        hardware.inPreferredConfig = Bitmap.Config.HARDWARE;
        assertEquals(Bitmap.Config.HARDWARE,
                BitmapFactory.decodeByteArray(png, 0, png.length, hardware).getConfig());

        final BitmapFactory.Options reuse = new BitmapFactory.Options();
        reuse.inBitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        reuse.inMutable = true;
        final Bitmap reused = BitmapFactory.decodeByteArray(png, 0, png.length, reuse);
        assertTrue("decoded into the given bitmap", reused == reuse.inBitmap);
        assertRedBlue(reused, W, H);

        final BitmapFactory.Options scaled = new BitmapFactory.Options();
        scaled.inDensity = 160;
        scaled.inTargetDensity = 320;
        assertRedBlue(BitmapFactory.decodeByteArray(png, 0, png.length, scaled), W * 2, H * 2);
    }

    @Test
    public void brokenBytesFailAsBefore() throws Exception {
        final byte[] broken = png();
        broken[20] ^= 0x55; // The header's checksum no longer matches.
        final int[] before = DecodeGate.countsForTest();
        assertNull(BitmapFactory.decodeByteArray(broken, 0, broken.length));
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(broken));
            fail("decoded");
        } catch (ImageDecoder.DecodeException e) {
            assertEquals(ImageDecoder.DecodeException.SOURCE_MALFORMED_DATA, e.getError());
        }
        assertEquals("nothing was decoded", 0, since(before)[0]);
    }

    // ImageDecoder

    @Test
    public void imageDecoderEverySource() throws Exception {
        final byte[] png = png();
        assertRedBlue(ImageDecoder.decodeBitmap(ImageDecoder.createSource(png)), W, H);
        assertRedBlue(ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(png))), W, H);
        final ByteBuffer direct = ByteBuffer.allocateDirect(png.length);
        direct.put(png).flip();
        assertRedBlue(ImageDecoder.decodeBitmap(ImageDecoder.createSource(direct)), W, H);
        final File file = file(png);
        try {
            assertRedBlue(ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)), W, H);
            assertRedBlue(ImageDecoder.decodeBitmap(ImageDecoder.createSource(() -> new AssetFileDescriptor(
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0,
                    AssetFileDescriptor.UNKNOWN_LENGTH))), W, H);
            assertRedBlue(ImageDecoder.decodeBitmap(ImageDecoder.createSource(mContext.getContentResolver(),
                    Uri.fromFile(file))), W, H);
        } finally {
            file.delete();
        }
        ProbeProvider.ICONS.put("gate", png);
        DecodeGate.resetForTest();
        final int[] before = DecodeGate.countsForTest();
        assertRedBlue(ImageDecoder.decodeBitmap(ImageDecoder.createSource(mContext.getContentResolver(),
                Uri.parse("content://" + ProbeProvider.AUTHORITY + "/icon/gate"))), W, H);
        assertTrue(Arrays.equals(new int[] {1, 1, 0}, since(before)));
    }

    @Test
    public void imageDecoderListenerSettings() throws Exception {
        final byte[] png = png();
        final AtomicReference<ImageDecoder.ImageInfo> info = new AtomicReference<>();
        final Bitmap half = ImageDecoder.decodeBitmap(ImageDecoder.createSource(png), (decoder, i, s) -> {
            info.set(i);
            decoder.setTargetSize(W / 2, H / 2);
        });
        assertRedBlue(half, W / 2, H / 2);
        assertEquals(W, info.get().getSize().getWidth());
        assertEquals("image/png", info.get().getMimeType());
        assertFalse(info.get().isAnimated());

        final Bitmap right = ImageDecoder.decodeBitmap(ImageDecoder.createSource(png), (decoder, i, s) -> {
            decoder.setCrop(new Rect(W / 2, 0, W, H));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
        assertEquals(W / 2, right.getWidth());
        assertColor(Color.BLUE, right.getPixel(2, H / 2));

        final Bitmap hardware = ImageDecoder.decodeBitmap(ImageDecoder.createSource(png),
                (decoder, i, s) -> decoder.setAllocator(ImageDecoder.ALLOCATOR_HARDWARE));
        assertEquals(Bitmap.Config.HARDWARE, hardware.getConfig());

        final Bitmap processed = ImageDecoder.decodeBitmap(ImageDecoder.createSource(png), (decoder, i, s) ->
                decoder.setPostProcessor(canvas -> {
                    canvas.drawColor(Color.GREEN);
                    return PixelFormat.OPAQUE;
                }));
        assertColor(Color.GREEN, processed.copy(Bitmap.Config.ARGB_8888, false).getPixel(1, 1));
        assertFalse(processed.hasAlpha());
    }

    @Test
    public void imageDecoderDrawablesAndAnimation() throws Exception {
        final Drawable drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(png()));
        assertTrue(drawable instanceof BitmapDrawable);
        assertRedBlue(((BitmapDrawable) drawable).getBitmap(), W, H);
        // An animated image is its first frame (the VM makes no animation here).
        final Drawable gif = ImageDecoder.decodeDrawable(ImageDecoder.createSource(GIF));
        assertTrue(gif instanceof BitmapDrawable);
        final Drawable fromStream = Drawable.createFromStream(new ByteArrayInputStream(png()), "gate");
        assertRedBlue(((BitmapDrawable) fromStream).getBitmap(), W, H);
    }

    // BitmapRegionDecoder and Movie

    @Test
    public void regionDecoderEverySource() throws Exception {
        final byte[] png = png();
        final BitmapRegionDecoder fromBytes = BitmapRegionDecoder.newInstance(png, 0, png.length);
        assertEquals(W, fromBytes.getWidth());
        assertEquals(H, fromBytes.getHeight());
        final Bitmap left = fromBytes.decodeRegion(new Rect(0, 0, W / 2, H), null);
        assertEquals(W / 2, left.getWidth());
        assertColor(Color.RED, left.getPixel(W / 4, H / 2));
        final BitmapFactory.Options sampled = new BitmapFactory.Options();
        sampled.inSampleSize = 2;
        final Bitmap right = fromBytes.decodeRegion(new Rect(W / 2, 0, W, H), sampled);
        assertEquals(W / 4, right.getWidth());
        assertColor(Color.BLUE, right.getPixel(W / 8, H / 4));
        fromBytes.recycle();
        assertTrue(fromBytes.isRecycled());

        final BitmapRegionDecoder fromStream = BitmapRegionDecoder.newInstance(new ByteArrayInputStream(png));
        assertColor(Color.BLUE, fromStream.decodeRegion(new Rect(W / 2, 0, W, H), null).getPixel(4, 4));
        final File file = file(png);
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
            final BitmapRegionDecoder fromFd = BitmapRegionDecoder.newInstance(pfd);
            assertColor(Color.RED, fromFd.decodeRegion(new Rect(0, 0, 8, 8), null).getPixel(4, 4));
        } finally {
            file.delete();
        }
    }

    // A photo-like test image: smooth gradients with fine detail (a one-pixel grid and rings), so a region decoded
    // with less detail than the stored image shows in the comparison.
    private static Bitmap photo(int w, int h) {
        final Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        final int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int ring = (int) (Math.sqrt((x - w / 2.0) * (x - w / 2.0) + (y - h / 2.0) * (y - h / 2.0))) % 7;
                final int grid = (x % 4 == 0 || y % 4 == 0) ? 60 : 0;
                row[x] = Color.rgb((x * 255 / w + grid) & 0xff, (y * 255 / h + ring * 20) & 0xff, ((x ^ y) & 0x3f) * 4);
            }
            bitmap.setPixels(row, 0, w, 0, y, w, 1);
        }
        return bitmap;
    }

    private static byte[] encoded(Bitmap bitmap, Bitmap.CompressFormat format) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(format, 95, out));
        return out.toByteArray();
    }

    // The mean difference of every channel of two bitmaps of the same size.
    private static double meanDiff(Bitmap a, Bitmap b) {
        assertEquals(a.getWidth(), b.getWidth());
        assertEquals(a.getHeight(), b.getHeight());
        final int n = a.getWidth() * a.getHeight();
        final int[] pa = new int[n];
        final int[] pb = new int[n];
        a.getPixels(pa, 0, a.getWidth(), 0, 0, a.getWidth(), a.getHeight());
        b.getPixels(pb, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
        double total = 0;
        for (int i = 0; i < n; i++) {
            for (int shift = 0; shift < 32; shift += 8) {
                total += Math.abs(((pa[i] >>> shift) & 0xff) - ((pb[i] >>> shift) & 0xff));
            }
        }
        return total / (n * 4.0);
    }

    private static Bitmap region(byte[] bytes, Rect rect, int sample, boolean gated) throws Exception {
        DecodeGate.setForcedForTest(gated);
        try {
            final BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.length);
            final BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            final Bitmap b = decoder.decodeRegion(rect, opts);
            decoder.recycle();
            return b;
        } finally {
            DecodeGate.setForcedForTest(true);
        }
    }

    @Test
    public void regionsMatchStockAtFullDetail() throws Exception {
        final Bitmap photo = photo(2400, 1600);
        final byte[] jpeg = encoded(photo, Bitmap.CompressFormat.JPEG);
        final byte[] png = encoded(photo, Bitmap.CompressFormat.PNG);
        final byte[] webp = encoded(photo, Bitmap.CompressFormat.WEBP_LOSSLESS);
        final byte[][] files = {jpeg, png, webp, turnedJpeg()};
        final String[] names = {"jpeg", "png", "webp", "turned jpeg"};
        // Even and odd corners and sizes, the whole image, a rect past the right and bottom edges (only its part in
        // the image) and one that starts above and left of it (the rect's size, the rest transparent).
        final Rect[] rects = {new Rect(1000, 600, 1512, 1112), new Rect(3, 5, 803, 605),
                new Rect(1801, 1201, 2400, 1600), new Rect(0, 0, 2400, 1600), new Rect(2301, 1499, 2501, 1701),
                new Rect(-51, -31, 149, 169)};
        final java.util.List<String> failed = new java.util.ArrayList<>();
        for (int f = 0; f < files.length; f++) {
            for (Rect rect : f == 3 ? new Rect[] {new Rect(5, 3, 37, 29)} : rects) {
                for (int sample : new int[] {1, 2, 3, 4, 6, 16}) {
                    final String what = names[f] + " " + rect.toShortString() + " /" + sample;
                    final Bitmap stock = region(files[f], rect, sample, false);
                    final long start = SystemClock.uptimeMillis();
                    final Bitmap gated = region(files[f], rect, sample, true);
                    final long took = SystemClock.uptimeMillis() - start;
                    if (gated == null) {
                        failed.add(what + ": none");
                        continue;
                    }
                    if (gated.getWidth() != stock.getWidth() || gated.getHeight() != stock.getHeight()) {
                        failed.add(what + ": " + gated.getWidth() + "x" + gated.getHeight() + ", stock "
                                + stock.getWidth() + "x" + stock.getHeight());
                        continue;
                    }
                    final double diff = meanDiff(stock, gated);
                    Log.i(TAG, "region " + what + ": mean difference " + String.format("%.2f", diff) + ", " + took
                            + " ms");
                    if (diff > 3.0) {
                        failed.add(what + " differs by " + String.format("%.2f", diff));
                    }
                }
            }
        }
        assertTrue(failed.toString(), failed.isEmpty());
    }

    @Test
    public void moviesAreNotMadeInAGatedProcess() {
        assertNull(Movie.decodeByteArray(GIF, 0, GIF.length));
        assertNull(Movie.decodeStream(new ByteArrayInputStream(GIF)));
        DecodeGate.setForcedForTest(false);
        assertNotNull("as before without the gate", Movie.decodeByteArray(GIF, 0, GIF.length));
    }

    // Exemption

    @Test
    public void exemptionIsDecidedFromTheOpenFile() throws Exception {
        assumeTrue("verified boot does not enforce dm-verity on this boot",
                "enforcing".equals(SystemProperties.get("ro.boot.veritymode")));
        final File system = new File("/system/framework/framework-res.apk"); // any app may read it
        try (FileInputStream in = new FileInputStream(system)) {
            assertTrue("a file of the system partition", VerifiedSystemFiles.holds(in.getFD()));
        }
        final File data = file(png());
        final File link = new File(mContext.getCacheDir(), "gate-link");
        link.delete();
        try {
            try (FileInputStream in = new FileInputStream(data)) {
                assertFalse("a file of /data", VerifiedSystemFiles.holds(in.getFD()));
                // /proc/self/fd/N opens the same /data file again: still /data.
                final int n = in.getFD().getInt$();
                try (FileInputStream again = new FileInputStream("/proc/self/fd/" + n)) {
                    assertFalse("/proc/self/fd", VerifiedSystemFiles.holds(again.getFD()));
                }
            }
            // A link in /data that points into /system: the bytes really are the system file's.
            Os.symlink(system.getPath(), link.getPath());
            try (FileInputStream in = new FileInputStream(link)) {
                assertTrue("a link to a system file", VerifiedSystemFiles.holds(in.getFD()));
            }
            // A link in /data named like a system path does not make a /data file one.
            final File named = new File(mContext.getCacheDir(), "system");
            named.mkdirs();
            final File fake = new File(named, "build.prop");
            try (FileOutputStream out = new FileOutputStream(fake)) {
                out.write(1);
            }
            try (FileInputStream in = new FileInputStream(fake)) {
                assertFalse("a /data file under a system-like name", VerifiedSystemFiles.holds(in.getFD()));
            }
        } finally {
            data.delete();
            link.delete();
        }
        final FileDescriptor memory = Os.memfd_create("gate", 0);
        try {
            assertFalse("a memory file", VerifiedSystemFiles.holds(memory));
        } finally {
            Os.close(memory);
        }
        final FileDescriptor[] pipe = Os.pipe();
        try {
            assertFalse("a pipe", VerifiedSystemFiles.holds(pipe[0]));
        } finally {
            Os.close(pipe[0]);
            Os.close(pipe[1]);
        }
    }

    @Test
    public void exemptSystemFilesDecodeAsBefore() throws Exception {
        assumeTrue("enforcing".equals(SystemProperties.get("ro.boot.veritymode")));
        final File image = findSystemImage(new File("/product/media"), 3);
        assumeTrue("no image file on the system partitions", image != null);
        final int[] before = DecodeGate.countsForTest();
        try (FileInputStream in = new FileInputStream(image)) {
            assertNotNull(BitmapFactory.decodeFileDescriptor(in.getFD()));
        }
        assertEquals("decoded here, not in the VM", 0, since(before)[0] + since(before)[1]);
    }

    private static File findSystemImage(File dir, int depth) {
        final File[] files = dir.listFiles();
        if (files == null || depth < 0) {
            return null;
        }
        for (File f : files) {
            if (f.isFile() && (f.getName().endsWith(".png") || f.getName().endsWith(".jpg"))) {
                return f;
            }
        }
        for (File f : files) {
            if (f.isDirectory()) {
                final File found = findSystemImage(f, depth - 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // A two-frame GIF (1 x 1, black then white, 100 ms each, looping forever).
    private static final byte[] GIF2 = {
        'G', 'I', 'F', '8', '9', 'a', 1, 0, 1, 0, (byte) 0x80, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, (byte) 0xff,
        0x21, (byte) 0xff, 0x0b, 'N', 'E', 'T', 'S', 'C', 'A', 'P', 'E', '2', '.', '0', 3, 1, 0, 0, 0,
        0x21, (byte) 0xf9, 4, 0, 10, 0, 0, 0, ',', 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0,
        0x21, (byte) 0xf9, 4, 0, 10, 0, 0, 0, ',', 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x4c, 1, 0,
        ';',
    };

    @Test
    public void animatedImagesAreAnimatedFromTheVmFrames() throws Exception {
        final Drawable drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(GIF2));
        assertTrue("animated: " + drawable, drawable instanceof android.graphics.drawable.Animatable2);
        assertEquals(1, drawable.getIntrinsicWidth());
        final android.graphics.drawable.Animatable2 animation = (android.graphics.drawable.Animatable2) drawable;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            drawable.setBounds(0, 0, 1, 1);
            animation.start();
        });
        assertTrue(animation.isRunning());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(animation::stop);
        assertFalse(animation.isRunning());
        // decodeBitmap gives the first frame, as before.
        final Bitmap first = ImageDecoder.decodeBitmap(ImageDecoder.createSource(GIF2),
                (decoder, info, src) -> {
                    assertTrue(info.isAnimated());
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
        assertColor(Color.BLACK, first.getPixel(0, 0));
    }

    @Test
    public void unpremultipliedAndAlphaMasks() throws Exception {
        final Bitmap half = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        half.eraseColor(Color.argb(128, 255, 0, 0));
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(half.compress(Bitmap.CompressFormat.PNG, 100, out));
        final byte[] png = out.toByteArray();
        final BitmapFactory.Options unpremul = new BitmapFactory.Options();
        unpremul.inPremultiplied = false;
        final Bitmap b = BitmapFactory.decodeByteArray(png, 0, png.length, unpremul);
        assertFalse(b.isPremultiplied());
        final Bitmap u = ImageDecoder.decodeBitmap(ImageDecoder.createSource(png),
                (decoder, info, src) -> decoder.setUnpremultipliedRequired(true));
        assertFalse(u.isPremultiplied());
        assertEquals(255, Color.red(u.getPixel(1, 1)));

        // An alpha mask, as Android makes one: only from a one-channel image. A colour PNG of grey pixels stays in
        // colour, a greyscale PNG becomes a mask, through the gate as without it.
        final Bitmap grey = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        grey.eraseColor(Color.rgb(100, 100, 100));
        out.reset();
        assertTrue(grey.compress(Bitmap.CompressFormat.PNG, 100, out));
        final byte[] rgbGrey = out.toByteArray();
        final byte[] oneChannel = greyPng(4, 4, 100);
        for (byte[] file : new byte[][] {rgbGrey, oneChannel}) {
            DecodeGate.setForcedForTest(false);
            final Bitmap.Config stock = maskOf(file).getConfig();
            final BitmapFactory.Options a8 = new BitmapFactory.Options();
            a8.inPreferredConfig = Bitmap.Config.ALPHA_8;
            final Bitmap.Config stockFactory = BitmapFactory.decodeByteArray(file, 0, file.length, a8).getConfig();
            DecodeGate.setForcedForTest(true);
            assertEquals("ImageDecoder alpha mask", stock, maskOf(file).getConfig());
            assertEquals("BitmapFactory ALPHA_8", stockFactory,
                    BitmapFactory.decodeByteArray(file, 0, file.length, a8).getConfig());
        }
        DecodeGate.setForcedForTest(false);
        assertEquals(Bitmap.Config.ALPHA_8, maskOf(oneChannel).getConfig());
        DecodeGate.setForcedForTest(true);
        final Bitmap mask = maskOf(oneChannel);
        assertEquals(Bitmap.Config.ALPHA_8, mask.getConfig());
        assertEquals(100, Color.alpha(mask.getPixel(1, 1)));
    }

    private static Bitmap maskOf(byte[] file) throws Exception {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), (decoder, info, src) -> {
            decoder.setDecodeAsAlphaMaskEnabled(true);
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }

    // A greyscale PNG (one channel, colour type 0) of one grey level.
    private static byte[] greyPng(int w, int h, int level) throws Exception {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int y = 0; y < h; y++) {
            raw.write(0); // no filter
            for (int x = 0; x < w; x++) {
                raw.write(level);
            }
        }
        final ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        try (java.util.zip.DeflaterOutputStream z = new java.util.zip.DeflaterOutputStream(zipped)) {
            z.write(raw.toByteArray());
        }
        final ByteArrayOutputStream png = new ByteArrayOutputStream();
        png.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        chunk(png, "IHDR", ByteBuffer.allocate(13).putInt(w).putInt(h).put((byte) 8).put((byte) 0).put((byte) 0)
                .put((byte) 0).put((byte) 0).array());
        chunk(png, "IDAT", zipped.toByteArray());
        chunk(png, "IEND", new byte[0]);
        return png.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream png, String type, byte[] data) throws Exception {
        final byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        png.write(ByteBuffer.allocate(4).putInt(data.length).array());
        png.write(t);
        png.write(data);
        final java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(t);
        crc.update(data);
        png.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    /**
     * Framework drawables (a nine-patch inside a layer list, the default app icon's bitmap XML) inflate and decode in
     * this process, as before, whether the decode service runs or not: resources of the system image are never sent
     * to the VM, so the nine-patch data stays.
     */
    @Test
    public void frameworkDrawablesInflateHereUpOrStopped() throws Exception {
        final android.content.res.Resources res = mContext.getResources();
        final int layers = res.getIdentifier("cab_background_top_material", "drawable", "android");
        final int ninePatch = res.getIdentifier("cab_background_top_mtrl_alpha", "drawable", "android");
        final int appIcon = res.getIdentifier("sym_def_app_icon", "drawable", "android");
        assertTrue(layers != 0 && ninePatch != 0 && appIcon != 0);
        final int[] before = DecodeGate.countsForTest();
        try (android.content.res.XmlResourceParser xml = res.getXml(layers)) {
            assertNotNull(Drawable.createFromXml(res, xml, mContext.getTheme()));
        }
        final android.util.TypedValue value = new android.util.TypedValue();
        res.getValue(appIcon, value, true);
        if (value.string != null && value.string.toString().endsWith(".xml")) {
            try (android.content.res.XmlResourceParser xml = res.getXml(appIcon)) {
                assertNotNull(Drawable.createFromXml(res, xml, mContext.getTheme()));
            }
        }
        try (java.io.InputStream in = res.openRawResource(ninePatch, value)) {
            final Bitmap b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(res, in, value.density),
                    (decoder, info, src) -> decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
            assertNotNull("nine-patch data", b.getNinePatchChunk());
        }
        assertNotNull("nine-patch data", BitmapFactory.decodeResource(res, ninePatch).getNinePatchChunk());
        assertTrue("decoded here, not in the VM", Arrays.equals(new int[] {0, 0, 0}, since(before)));
    }

    // A JPEG of the red and blue image with an EXIF orientation of 6 (turn 90 degrees clockwise to show).
    private static byte[] turnedJpeg() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(redBlue().compress(Bitmap.CompressFormat.JPEG, 95, out));
        final byte[] jpeg = out.toByteArray();
        final byte[] exif = {
            (byte) 0xff, (byte) 0xe1, 0, 0x22, 'E', 'x', 'i', 'f', 0, 0,
            'I', 'I', 0x2a, 0, 8, 0, 0, 0, 1, 0,
            0x12, 0x01, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0,
            0, 0, 0, 0,
        };
        final byte[] turned = new byte[jpeg.length + exif.length];
        System.arraycopy(jpeg, 0, turned, 0, 2);
        System.arraycopy(exif, 0, turned, 2, exif.length);
        System.arraycopy(jpeg, 2, turned, 2 + exif.length, jpeg.length - 2);
        return turned;
    }

    @Test
    public void turnedJpegHeaderIsUpright() throws Exception {
        final byte[] jpeg = turnedJpeg();
        final AtomicReference<ImageDecoder.ImageInfo> info = new AtomicReference<>();
        final Bitmap upright = ImageDecoder.decodeBitmap(ImageDecoder.createSource(jpeg),
                (decoder, i, s) -> info.set(i));
        assertEquals("header width", H, info.get().getSize().getWidth());
        assertEquals("header height", W, info.get().getSize().getHeight());
        assertEquals(H, upright.getWidth());
        assertEquals(W, upright.getHeight());
        // BitmapFactory does not turn: the stored size and pixels.
        final Bitmap stored = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        assertEquals(W, stored.getWidth());
        assertEquals(H, stored.getHeight());
    }

    @Test
    public void aCallNotWaitedForFinishesInTheBackground() throws Exception {
        final byte[] png = png();
        DecodeGate.setWaitForTest(0L);
        final int[] before = DecodeGate.countsForTest();
        assertNull("not waited for", BitmapFactory.decodeByteArray(png, 0, png.length));
        assertTrue("counted as not waited for", DecodeGate.countsForTest()[3] > before[3]);
        // The probe finished in the background: the next call has it at once and sends the decode.
        final long until = SystemClock.uptimeMillis() + 2 * DecodeGate.SERVER_MS;
        Bitmap got = null;
        while (got == null && SystemClock.uptimeMillis() < until) {
            SystemClock.sleep(100);
            got = BitmapFactory.decodeByteArray(png, 0, png.length);
        }
        assertRedBlue(got, W, H);
        assertTrue("answered from the cache", hitsSince(before) >= 2);
        final long start = SystemClock.uptimeMillis();
        assertRedBlue(BitmapFactory.decodeByteArray(png, 0, png.length), W, H);
        Log.i(TAG, "the same call again, from the cache: " + (SystemClock.uptimeMillis() - start) + " ms");
    }

    @Test
    public void theGatesOwnThreadsNeverGoThroughItAgain() throws Exception {
        final byte[] png = png();
        final AtomicReference<Bitmap> got = new AtomicReference<>(Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8));
        final Thread t = new Thread(() -> {
            DecodeGate.markGateThread();
            got.set(BitmapFactory.decodeByteArray(png, 0, png.length));
        });
        t.start();
        t.join();
        assertNull("failed closed on the gate's own thread", got.get());
    }

    @Test
    public void anOrdinaryCallerIsRefused() throws Exception {
        // The shell (uid 2000) is neither the system nor a system app. Under adb root the shell's commands run as
        // root, which a debuggable build lets through: the call is made as uid 2000 then.
        final String asShell = shellOutput("id -u").trim().equals("0") ? "su 2000 " : "";
        final android.os.ParcelFileDescriptor[] out = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommandRwe(asShell + "content call --uri " + DecodeGate.URI + " --method probe");
        out[1].close();
        final String text;
        try (java.io.InputStream o = new ParcelFileDescriptor.AutoCloseInputStream(out[0]);
                java.io.InputStream e = new ParcelFileDescriptor.AutoCloseInputStream(out[2])) {
            text = new String(o.readAllBytes()) + new String(e.readAllBytes());
        }
        Log.i(TAG, "shell call: " + text.trim());
        assertTrue("refused: " + text, text.contains("SecurityException"));
    }

    private static String shellOutput(String command) throws Exception {
        final ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(command);
        try (java.io.InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            return new String(in.readAllBytes());
        }
    }

    // Threads and time

    @Test
    public void mainThreadTimeWithTheVmUp() {
        final byte[] png = png();
        final long[] times = new long[5];
        for (int i = 0; i < times.length; i++) {
            DecodeGate.resetForTest(); // Each a real round trip to the VM, not the cache.
            final AtomicReference<Bitmap> got = new AtomicReference<>();
            final int index = i;
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                final long start = SystemClock.uptimeMillis();
                got.set(BitmapFactory.decodeByteArray(png, 0, png.length));
                times[index] = SystemClock.uptimeMillis() - start;
            });
            // The whole call, its probe and its pixels, within the main thread's time.
            assertTrue("main thread took " + times[i] + " ms", times[i] <= DecodeGate.MAIN_MS + MARGIN_MS);
            assertRedBlue(got.get(), W, H);
        }
        Log.i(TAG, "main thread, BitmapFactory, VM up: " + Arrays.toString(times) + " ms");
        final long[] cached = new long[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final long start = SystemClock.uptimeMillis();
            BitmapFactory.decodeByteArray(png, 0, png.length);
            cached[0] = SystemClock.uptimeMillis() - start;
        });
        Log.i(TAG, "main thread, BitmapFactory, the same bytes again (cache): " + cached[0] + " ms");
    }

    @Test
    public void processWithoutTheGateDecodesAsBefore() throws Exception {
        DecodeGate.setForcedForTest(false);
        final byte[] png = png();
        final int[] before = DecodeGate.countsForTest();
        assertRedBlue(BitmapFactory.decodeByteArray(png, 0, png.length), W, H);
        assertRedBlue(ImageDecoder.decodeBitmap(ImageDecoder.createSource(png)), W, H);
        assertEquals(W, BitmapRegionDecoder.newInstance(png, 0, png.length).getWidth());
        assertTrue(Arrays.equals(new int[] {0, 0, 0}, since(before)));
    }

    @Test
    public void failsClosedWhileTheDecodeServiceIsStopped() throws Exception {
        assumeTrue("run with -e decodeGateStopped true while the decode service is stopped",
                "true".equals(InstrumentationRegistry.getArguments().getString("decodeGateStopped")));
        final byte[] png = png();
        long start = SystemClock.uptimeMillis();
        assertNull(BitmapFactory.decodeByteArray(png, 0, png.length));
        long took = SystemClock.uptimeMillis() - start;
        Log.i(TAG, "worker thread, BitmapFactory, decode service stopped: " + took + " ms");
        assertTrue("took " + took + " ms", took <= DecodeGate.WORKER_MS + MARGIN_MS);
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(png));
            fail("decoded");
        } catch (ImageDecoder.DecodeException e) {
            assertEquals(ImageDecoder.DecodeException.SOURCE_EXCEPTION, e.getError());
        }
        try {
            BitmapRegionDecoder.newInstance(png, 0, png.length);
            fail("made a region decoder");
        } catch (java.io.IOException e) {
            // Fails as for an image it cannot read.
        }
        final AtomicLong mainTook = new AtomicLong();
        final AtomicReference<Bitmap> got = new AtomicReference<>();
        DecodeGate.resetForTest();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final long s = SystemClock.uptimeMillis();
            got.set(BitmapFactory.decodeByteArray(png, 0, png.length));
            mainTook.set(SystemClock.uptimeMillis() - s);
        });
        Log.i(TAG, "main thread, BitmapFactory, decode service stopped: " + mainTook.get() + " ms");
        assertNull(got.get());
        assertTrue("main thread took " + mainTook.get() + " ms", mainTook.get() <= DecodeGate.MAIN_MS + MARGIN_MS);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final long s = SystemClock.uptimeMillis();
            got.set(BitmapFactory.decodeByteArray(png, 0, png.length));
            mainTook.set(SystemClock.uptimeMillis() - s);
        });
        Log.i(TAG, "main thread right after a wait ran out: " + mainTook.get() + " ms");
        assertTrue("main thread took " + mainTook.get() + " ms", mainTook.get() <= MARGIN_MS);
    }

    // Orientation, configs, shared memory files, assets of other APKs, the cache, lanes, large images

    // Red, blue, green and white quarters (top left, top right, bottom left, bottom right), W x H.
    private static Bitmap quarters() {
        final Bitmap bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final Paint paint = new Paint();
        final int[] colors = {Color.RED, Color.BLUE, Color.GREEN, Color.WHITE};
        for (int i = 0; i < 4; i++) {
            paint.setColor(colors[i]);
            canvas.drawRect((i % 2) * W / 2, (i / 2) * H / 2, (i % 2 + 1) * W / 2, (i / 2 + 1) * H / 2, paint);
        }
        return bitmap;
    }

    // A JPEG of {@code bitmap} with an EXIF segment holding only {@code orientation}.
    private static byte[] withOrientation(Bitmap bitmap, int orientation) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out));
        final byte[] jpeg = out.toByteArray();
        final byte[] exif = {
            (byte) 0xff, (byte) 0xe1, 0, 0x22, 'E', 'x', 'i', 'f', 0, 0,
            'I', 'I', 0x2a, 0, 8, 0, 0, 0, 1, 0,
            0x12, 0x01, 3, 0, 1, 0, 0, 0, (byte) orientation, 0, 0, 0,
            0, 0, 0, 0,
        };
        final byte[] oriented = new byte[jpeg.length + exif.length];
        System.arraycopy(jpeg, 0, oriented, 0, 2);
        System.arraycopy(exif, 0, oriented, 2, exif.length);
        System.arraycopy(jpeg, 2, oriented, 2 + exif.length, jpeg.length - 2);
        return oriented;
    }

    // What an EXIF orientation shows: mirrored left to right first for 2, 4, 5 and 7, then turned clockwise as
    // ExifInterface.getRotationDegrees says.
    private static Bitmap shown(Bitmap stored, int orientation) {
        final Matrix matrix = new Matrix();
        if (orientation == 2 || orientation == 4 || orientation == 5 || orientation == 7) {
            matrix.postScale(-1, 1);
        }
        final int[] degrees = {0, 0, 0, 180, 180, 270, 90, 90, 270};
        matrix.postRotate(degrees[orientation]);
        return Bitmap.createBitmap(stored, 0, 0, stored.getWidth(), stored.getHeight(), matrix, false);
    }

    // The colour at the middle of each quarter of {@code bitmap} matches {@code want}'s.
    private static void assertQuarters(String what, Bitmap want, Bitmap got) {
        assertNotNull(what, got);
        assertEquals(what + " width", want.getWidth(), got.getWidth());
        assertEquals(what + " height", want.getHeight(), got.getHeight());
        final Bitmap soft = got.getConfig() == Bitmap.Config.HARDWARE ? got.copy(Bitmap.Config.ARGB_8888, false) : got;
        for (int i = 0; i < 4; i++) {
            final int x = (2 * (i % 2) + 1) * want.getWidth() / 4;
            final int y = (2 * (i / 2) + 1) * want.getHeight() / 4;
            assertColor(want.getPixel(x, y), soft.getPixel(x, y));
        }
    }

    @Test
    public void mirroredAndTurnedJpegsAreStoredForBitmapFactoryAndShownForImageDecoder() throws Exception {
        final Bitmap stored = quarters();
        for (int orientation = 1; orientation <= 8; orientation++) {
            final byte[] jpeg = withOrientation(stored, orientation);
            assertQuarters("BitmapFactory, orientation " + orientation, stored,
                    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length));
            final BitmapRegionDecoder regions = BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.length);
            assertQuarters("region, orientation " + orientation, stored,
                    regions.decodeRegion(new Rect(0, 0, W, H), null));
            regions.recycle();
            assertQuarters("ImageDecoder, orientation " + orientation, shown(stored, orientation),
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(jpeg)));
        }
    }

    @Test
    public void rgb565OnlyForOpaqueImages() throws Exception {
        // The left half transparent: RGB_565 would turn it black.
        final Bitmap half = redBlue();
        half.eraseColor(Color.TRANSPARENT);
        new Canvas(half).drawRect(W / 2, 0, W, H, paint(Color.BLUE));
        final byte[] png = encoded(half, Bitmap.CompressFormat.PNG);
        final BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        final Bitmap withAlpha = BitmapFactory.decodeByteArray(png, 0, png.length, opts);
        assertEquals(Bitmap.Config.ARGB_8888, withAlpha.getConfig());
        assertEquals(Bitmap.Config.ARGB_8888, opts.outConfig);
        assertTrue(withAlpha.hasAlpha());
        assertEquals(0, Color.alpha(withAlpha.getPixel(W / 4, H / 2)));
        assertColor(Color.BLUE, withAlpha.getPixel(W * 3 / 4, H / 2));
        // An opaque JPEG and an opaque PNG take RGB_565 and say they have no alpha.
        for (byte[] opaque : new byte[][] {encoded(redBlue(), Bitmap.CompressFormat.JPEG), png()}) {
            final BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            final Bitmap b = BitmapFactory.decodeByteArray(opaque, 0, opaque.length, o);
            assertEquals(Bitmap.Config.RGB_565, b.getConfig());
            assertRedBlue(b, W, H);
            assertFalse(BitmapFactory.decodeByteArray(opaque, 0, opaque.length).hasAlpha());
        }
    }

    private static Paint paint(int color) {
        final Paint paint = new Paint();
        paint.setColor(color);
        return paint;
    }

    @Test
    public void overlappingRequestsOnOneMemoryFileStayApart() throws Exception {
        final Bitmap photo = photo(2400, 1600);
        final byte[] png = encoded(photo, Bitmap.CompressFormat.PNG);
        final Rect[] tiles = new Rect[12];
        for (int i = 0; i < tiles.length; i++) {
            tiles[i] = new Rect((i % 4) * 600, (i / 4) * 500, (i % 4) * 600 + 512, (i / 4) * 500 + 512);
        }
        final BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(png, 0, png.length);
        try {
            // Every tile asked at once and none waited for: the requests read the region decoder's one memory file
            // side by side in the system server and the decode service.
            DecodeGate.setWaitForTest(0L);
            for (Rect tile : tiles) {
                decoder.decodeRegion(tile, null);
            }
            DecodeGate.setWaitForTest(null);
            SystemClock.sleep(DecodeGate.SERVER_MS);
            // Then each tile is the stored image's, from the cache or decoded again, and none was refused.
            final int failuresBefore = DecodeGate.countsForTest()[2];
            for (Rect tile : tiles) {
                final Bitmap got = decoder.decodeRegion(tile, null);
                assertNotNull("tile " + tile, got);
                final Bitmap want = Bitmap.createBitmap(photo, tile.left, tile.top, tile.width(), tile.height());
                final double diff = meanDiff(want, got);
                assertTrue("tile " + tile + " differs by " + diff, diff <= 3.0);
            }
            assertEquals("no tile refused", failuresBefore, DecodeGate.countsForTest()[2]);
        } finally {
            decoder.recycle();
        }
    }

    @Test
    public void assetsOfAnotherApkGoThroughTheGateOwnStayHere() throws Exception {
        final String asset = "images/android-logo-mask.png"; // in framework-res
        // This app's own AssetManager (the system image and this APK): decoded here as before.
        int[] before = DecodeGate.countsForTest();
        try (java.io.InputStream own = mContext.getAssets().open(asset)) {
            assertNotNull(BitmapFactory.decodeStream(own));
        }
        try (java.io.InputStream own = mContext.getResources().openRawResource(
                de.diamaneos.decode.client.tests.R.drawable.vm_test_red)) {
            assertNotNull(BitmapFactory.decodeStream(own));
        }
        assertTrue("own assets decoded here", Arrays.equals(new int[] {0, 0, 0}, since(before)));
        // An AssetManager that also holds a copy of framework-res outside the system image: through the gate.
        final File copy = new File(mContext.getCacheDir(), "foreign.apk");
        java.nio.file.Files.copy(new File("/system/framework/framework-res.apk").toPath(), copy.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        final android.content.res.AssetManager foreign = new android.content.res.AssetManager.Builder()
                .addApkAssets(android.content.res.ApkAssets.loadFromPath(copy.getPath())).build();
        try {
            before = DecodeGate.countsForTest();
            assertNotNull(BitmapFactory.decodeStream(foreign.open(asset)));
            assertTrue("BitmapFactory in the VM", since(before)[0] >= 1);
            DecodeGate.resetForTest();
            before = DecodeGate.countsForTest();
            assertNotNull(ImageDecoder.decodeBitmap(ImageDecoder.createSource(foreign, asset)));
            assertTrue("ImageDecoder in the VM", since(before)[0] >= 1);
            DecodeGate.resetForTest();
            before = DecodeGate.countsForTest();
            final BitmapRegionDecoder regions = BitmapRegionDecoder.newInstance(foreign.open(asset));
            assertNotNull(regions.decodeRegion(new Rect(0, 0, 1, 1), null));
            regions.recycle();
            assertTrue("regions in the VM", since(before)[0] >= 1);
            assertNull("no movie from a gated asset", Movie.decodeStream(foreign.open(asset)));
        } finally {
            foreign.close();
            copy.delete();
        }
    }

    @Test
    public void answersAboveHalfTheCacheAreNotKeptAndKeepTheRest() throws Exception {
        final byte[] small = png();
        assertRedBlue(BitmapFactory.decodeByteArray(small, 0, small.length), W, H);
        // 2600 x 2600 pixels: 27 MB, above the whole cache.
        final Bitmap large = Bitmap.createBitmap(2600, 2600, Bitmap.Config.ARGB_8888);
        large.eraseColor(Color.GREEN);
        final byte[] big = encoded(large, Bitmap.CompressFormat.PNG);
        DecodeGate.setWaitForTest(15_000L);
        final int[] before = DecodeGate.countsForTest();
        for (int i = 0; i < 2; i++) {
            final Bitmap got = BitmapFactory.decodeByteArray(big, 0, big.length);
            assertNotNull(got);
            assertEquals(2600, got.getWidth());
        }
        // The probe (small) is kept, the pixels are not: decoded twice.
        assertEquals("decoded twice", 2, since(before)[0]);
        final int[] beforeSmall = DecodeGate.countsForTest();
        assertRedBlue(BitmapFactory.decodeByteArray(small, 0, small.length), W, H);
        assertTrue("the small image is still kept", hitsSince(beforeSmall) >= 2);
    }

    // Calls the provider directly as the gate does, for {@code origin}, a decode to fit {@code box}: true when taken.
    // The answer's name (with "!" when it carries no pixels) goes to {@code answers}.
    private boolean call(ParcelFileDescriptor bytes, String origin, String name, int box,
            java.util.List<String> answers) {
        try (DecodeGate.Input input = DecodeGate.snapshot(new android.content.res.AssetFileDescriptor(bytes, 0, -1))) {
        final android.os.Bundle extras = new android.os.Bundle();
        extras.putParcelable(DecodeGate.EXTRA_FD, input.fd());
        extras.putInt(DecodeGate.EXTRA_WIDTH, box);
        extras.putInt(DecodeGate.EXTRA_HEIGHT, box);
        extras.putLong(DecodeGate.EXTRA_TIME, DecodeGate.SERVER_MS);
        extras.putString(DecodeGate.EXTRA_AUTHORITY, origin);
        extras.putBinder(DecodeGate.EXTRA_CALLBACK, new android.os.IRemoteCallback.Stub() {
            @Override
            public void sendResult(android.os.Bundle reply) {
                answers.add(name + (reply.getParcelable(DecodeGate.EXTRA_BITMAP, Bitmap.class) != null ? ""
                        : "! " + reply.getString(DecodeGate.EXTRA_ERROR)));
            }
        }.asBinder());
        final android.os.Bundle taken = mContext.getContentResolver().call(DecodeGate.URI, DecodeGate.METHOD_DECODE,
                null, extras);
        return taken != null && taken.getBoolean(DecodeGate.EXTRA_TAKEN);
        } catch (java.io.IOException e) {
            throw new AssertionError("test input staging", e);
        }
    }

    @Test
    public void lanesAreCappedPerOriginAndAppAndTakenInTurn() throws Exception {
        // Slow enough to queue (above the decode service's small lane), quick enough that the 16 decodes one after
        // the other end within the provider's time: a detailed PNG of 2000 x 2000, decoded to about 512 px.
        final byte[] png = encoded(photo(2000, 2000), Bitmap.CompressFormat.PNG);
        final java.util.List<String> answers = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        int takenA = 0;
        int takenB = 0;
        boolean takenC;
        final FileDescriptor memory = Os.memfd_create("lanes", OsConstants.MFD_CLOEXEC);
        try (ParcelFileDescriptor bytes = ParcelFileDescriptor.dup(memory)) {
            for (int done = 0; done < png.length; ) {
                done += Os.write(memory, png, done, png.length - done);
            }
            Os.lseek(memory, 0, OsConstants.SEEK_SET);
            // Each request asks for its own size, so the decode service's result cache answers none of them: every
            // answer is the VM's, in the order the lanes give the VM the requests.
            for (int i = 0; i < 12; i++) {
                takenA += call(bytes, "lane-a", "a" + i, 500 + i, answers) ? 1 : 0;
            }
            for (int i = 0; i < 12; i++) {
                takenB += call(bytes, "lane-b", "b" + i, 520 + i, answers) ? 1 : 0;
            }
            takenC = call(bytes, "lane-c", "c0", 540, answers);
        } finally {
            Os.close(memory);
        }
        // An origin holds at most 8 places (running and waiting), an app 16.
        assertEquals(8, takenA);
        assertEquals(8, takenB);
        assertFalse("the app's places are full", takenC);
        final long until = SystemClock.uptimeMillis() + 4 * DecodeGate.SERVER_MS;
        while (answers.size() < takenA + takenB && SystemClock.uptimeMillis() < until) {
            SystemClock.sleep(50);
        }
        Log.i(TAG, "lane answers in order: " + answers);
        assertEquals(takenA + takenB, answers.size());
        // Every request read the one memory file side by side and got its pixels.
        for (String a : answers) {
            assertFalse(a, a.contains("!"));
        }
        // Taken in turn: the second origin's first answer came before the first origin's last.
        assertTrue(answers.toString(), answers.indexOf("b0") < answers.indexOf("a7"));
    }

    @Test
    public void imagesAboveTheVmLimitAtFullDetail() throws Exception {
        // 4000 x 2400 (9.6 MP): quarters, and one-pixel stripes in the top left that lost detail would blur.
        final int w = 4000;
        final int h = 2400;
        final Bitmap stored = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(stored);
        canvas.drawBitmap(quarters(), null, new Rect(0, 0, w, h), null);
        for (int x = 100; x < 164; x += 2) {
            canvas.drawRect(x, 100, x + 1, 164, paint(Color.BLACK));
            canvas.drawRect(x + 1, 100, x + 2, 164, paint(Color.WHITE));
        }
        DecodeGate.setWaitForTest(30_000L);
        final byte[] png = encoded(stored, Bitmap.CompressFormat.PNG);
        final Bitmap full = BitmapFactory.decodeByteArray(png, 0, png.length);
        assertQuarters("BitmapFactory 9.6 MP", stored, full);
        for (int x = 100; x < 164; x++) {
            assertColor(stored.getPixel(x, 132), full.getPixel(x, 132));
        }
        final BitmapRegionDecoder regions = BitmapRegionDecoder.newInstance(png, 0, png.length);
        final Bitmap all = regions.decodeRegion(new Rect(0, 0, w, h), null);
        regions.recycle();
        assertQuarters("whole region 9.6 MP", stored, all);
        // A turned JPEG of that size: upright at full size, from the stored stripes.
        final byte[] jpeg = withOrientation(stored, 6);
        final Bitmap upright = ImageDecoder.decodeBitmap(ImageDecoder.createSource(jpeg),
                (decoder, info, src) -> decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
        assertQuarters("ImageDecoder 9.6 MP turned", shown(stored, 6), upright);
    }
}
