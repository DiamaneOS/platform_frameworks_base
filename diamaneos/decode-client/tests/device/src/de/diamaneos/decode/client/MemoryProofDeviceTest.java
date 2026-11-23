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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.DecodeGate;
import android.graphics.DecodeMemory;
import android.graphics.Gainmap;
import android.os.Debug;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Peak memory of image decodes in the VM on the phone (the decode gate forced on, the decode service asked): large
 * Ultra HDR JPEGs through the decode client and the gate, a reused bitmap, and several large requests at once. Logs
 * the process's peak resident memory, the native heap, and the most DecodeMemory held, and checks the reservations
 * never pass their limit, every decode either has its image or a failure of the moment (never a crash), and nothing
 * stays reserved. Needs the decode service.
 */
@RunWith(AndroidJUnit4.class)
public class MemoryProofDeviceTest {
    private static final String TAG = "MemoryProof";
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @After
    public void asBefore() {
        DecodeGate.setForcedForTest(null);
        DecodeGate.setWaitForTest(null);
        DecodeGate.resetForTest();
    }

    // A width x height Ultra HDR JPEG: a gradient with a gain map of half the size (Bitmap.compress writes it).
    private File hdrJpeg(int width, int height, int seed) throws Exception {
        final Bitmap base = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                row[x] = Color.rgb((x * 255 / width + seed) & 0xff, (y * 255 / height) & 0xff, (x + y + seed) & 0xff);
            }
            base.setPixels(row, 0, width, 0, y, width, 1);
        }
        final Bitmap gain = Bitmap.createBitmap(width / 2, height / 2, Bitmap.Config.ARGB_8888);
        gain.eraseColor(Color.rgb(160, 160, 160));
        final Gainmap gainmap = new Gainmap(gain);
        gainmap.setRatioMax(4f, 4f, 4f);
        gainmap.setDisplayRatioForFullHdr(4f);
        base.setGainmap(gainmap);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(base.compress(Bitmap.CompressFormat.JPEG, 90, out));
        DecodeMemory.recycle(base, null);
        final File file = new File(mContext.getCacheDir(), "hdr-" + width + "x" + height + "-" + seed + ".jpg");
        try (FileOutputStream f = new FileOutputStream(file)) {
            f.write(out.toByteArray());
        }
        return file;
    }

    private static long peakRssKb() throws Exception {
        for (String line : Files.readAllLines(new File("/proc/self/status").toPath())) {
            if (line.startsWith("VmHWM:")) {
                return Long.parseLong(line.replaceAll("[^0-9]", ""));
            }
        }
        return -1;
    }

    private static long rssKb() throws Exception {
        for (String line : Files.readAllLines(new File("/proc/self/status").toPath())) {
            if (line.startsWith("VmRSS:")) {
                return Long.parseLong(line.replaceAll("[^0-9]", ""));
            }
        }
        return -1;
    }

    // Samples DecodeMemory.held() until stopped: the most seen.
    private static final class Sampler extends Thread {
        final AtomicLong most = new AtomicLong();
        volatile boolean stop;

        @Override
        public void run() {
            while (!stop) {
                most.accumulateAndGet(DecodeMemory.held(), Math::max);
                SystemClock.sleep(2);
            }
        }
    }

    @Test
    public void largeHdr_reuse_andRequestsAtOnce_stayWithinTheReservations() throws Exception {
        assumeTrue("needs the decode service",
                mContext.getPackageManager().getLaunchIntentForPackage("de.diamaneos.sanitizer") != null
                        || new File("/system_ext/priv-app/DiamaneOSSanitizer").exists()
                        || new File("/system/priv-app/DiamaneOSSanitizer").exists());
        final List<File> files = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            files.add(hdrJpeg(4000, 3000, i * 17)); // 12 MP each, a 3 MP gain map
        }
        final java.util.Map<File, int[]> nativeGainmaps = new java.util.HashMap<>();
        DecodeGate.setForcedForTest(false);
        try {
            for (File file : files) {
                final Bitmap stock = BitmapFactory.decodeFile(file.getPath());
                assertNotNull("native HDR fixture refused", stock);
                try {
                    assertTrue("native fixture lost its gain map", stock.hasGainmap());
                    final Bitmap map = stock.getGainmap().getGainmapContents();
                    nativeGainmaps.put(file, new int[] {map.getWidth(), map.getHeight()});
                } finally { DecodeMemory.recycle(stock, null); }
            }
        } finally { DecodeGate.setForcedForTest(true); }
        System.gc();
        final long rss0 = rssKb();
        final long hwm0 = peakRssKb();
        final long ledgerBefore = DecodeMemory.held();
        final Sampler sampler = new Sampler();
        sampler.start();
        final AtomicInteger made = new AtomicInteger();
        final AtomicInteger forNow = new AtomicInteger();
        final AtomicInteger gainmaps = new AtomicInteger();
        final List<String> errors = new ArrayList<>();
        try {
            // 1. The decode client: each at the VM's largest (8 MP), with its gain map, four at once.
            final List<Thread> threads = new ArrayList<>();
            for (File f : files) {
                final Thread t = new Thread(() -> {
                    try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)) {
                        final VmDecode.Decoded d = VmDecode.decode(mContext, fd, mContext.getPackageName(),
                                mContext.getUserId(), VmDecode.PRIORITY_USER_WAITING, 4000, 4000,
                                VmDecode.deadlineIn(30_000));
                        made.incrementAndGet();
                        if (d.bitmap.hasGainmap()) {
                            gainmaps.incrementAndGet();
                        }
                        Log.i(TAG, "client: " + d.bitmap.getWidth() + "x" + d.bitmap.getHeight() + " gain map "
                                + d.bitmap.hasGainmap());
                        DecodeMemory.recycle(d.bitmap, null);
                    } catch (VmDecode.UnavailableException e) {
                        forNow.incrementAndGet();
                        Log.i(TAG, "client, for now: " + e.getMessage());
                    } catch (Throwable e) {
                        synchronized (errors) {
                            errors.add("client: " + e);
                        }
                    }
                });
                threads.add(t);
                t.start();
            }
            for (Thread t : threads) {
                t.join(60_000);
            }
            // A provider turn follows complete HDR reply assembly while the input is still charged.
            ProbeProvider.ICONS.put("memory-outer-turn", Files.readAllBytes(files.get(0).toPath()));
            ProbeProvider.ORIENTATIONS.put("memory-outer-turn", 90);
            try {
                final Bitmap turned = VmDecode.loadThumbnail(mContext, mContext.getContentResolver(),
                        android.net.Uri.parse("content://" + ProbeProvider.AUTHORITY + "/icon/memory-outer-turn"),
                        mContext.getPackageName(), mContext.getUserId(), VmDecode.PRIORITY_USER_WAITING,
                        4000, 4000, VmDecode.deadlineIn(30_000));
                assertTrue("provider turn lost HDR", turned.hasGainmap());
                Log.i(TAG, "provider HDR turn: " + turned.getWidth() + "x" + turned.getHeight()
                        + ", live charged " + DecodeMemory.held() + ", RSS KiB " + rssKb() + ", peak KiB " + peakRssKb());
                DecodeMemory.recycle(turned, null);
            } finally {
                ProbeProvider.ICONS.remove("memory-outer-turn");
                ProbeProvider.ORIENTATIONS.remove("memory-outer-turn");
            }

            // An ImageDecoder reply is copied with its gain map before publication to the caller.
            DecodeGate.setForcedForTest(true);
            DecodeGate.resetForTest();
            final Bitmap copiedHdr = android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(files.get(0)), (decoder, info, source) -> {
                        decoder.setTargetSize(2000, 1500);
                        decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                    });
            assertTrue("copied reply lost HDR", copiedHdr.hasGainmap());
            Log.i(TAG, "copied HDR reply: " + copiedHdr.getWidth() + "x" + copiedHdr.getHeight()
                    + ", live charged " + DecodeMemory.held() + ", RSS KiB " + rssKb() + ", peak KiB " + peakRssKb());
            DecodeMemory.recycle(copiedHdr, null);

            // 2. The gate: BitmapFactory of the full 12 MP images (stripes at full detail), four at once.
            DecodeGate.setForcedForTest(true);
            DecodeGate.resetForTest();
            threads.clear();
            for (File f : files) {
                final Thread t = new Thread(() -> {
                    try {
                        final Bitmap b = BitmapFactory.decodeFile(f.getPath());
                        if (b != null) {
                            made.incrementAndGet();
                            Log.i(TAG, "gate: " + b.getWidth() + "x" + b.getHeight());
                            DecodeMemory.recycle(b, null);
                        } else {
                            forNow.incrementAndGet();
                        }
                    } catch (Throwable e) {
                        synchronized (errors) {
                            errors.add("gate: " + e);
                        }
                    }
                });
                threads.add(t);
                t.start();
            }
            for (Thread t : threads) {
                t.join(60_000);
            }
            // 3. A reused bitmap that held an HDR image, for each file in turn.
            final Bitmap reused = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888);
            reused.setGainmap(new Gainmap(Bitmap.createBitmap(2000, 1500, Bitmap.Config.ARGB_8888)));
            try {
                for (File f : files) {
                    final BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inMutable = true;
                    opts.inBitmap = reused;
                    try {
                        final Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
                        if (b != null) {
                            made.incrementAndGet();
                            assertSame(reused, b);
                            assertTrue("the new HDR image lost its gain map", b.hasGainmap());
                            assertEquals(nativeGainmaps.get(f)[0], b.getGainmap().getGainmapContents().getWidth());
                            assertEquals(nativeGainmaps.get(f)[1], b.getGainmap().getGainmapContents().getHeight());
                        } else {
                            forNow.incrementAndGet();
                        }
                    } catch (IllegalArgumentException e) {
                        forNow.incrementAndGet(); // Existing storage cannot fit this image.
                    }
                }
            } finally { DecodeMemory.recycle(reused, null); }
        } finally {
            sampler.stop = true;
            sampler.join();
            for (File f : files) {
                f.delete();
            }
        }
        final long hwm = peakRssKb();
        Log.i(TAG, "made " + made.get() + ", left out for now " + forNow.get() + ", client gain maps "
                + gainmaps.get() + ", DecodeMemory most held " + (sampler.most.get() >> 20) + " MiB of "
                + (DecodeMemory.MAX_BYTES >> 20) + ", RSS before " + (rss0 >> 10) + " MiB, peak RSS "
                + (hwm >> 10) + " MiB (before " + (hwm0 >> 10) + " MiB), native heap now "
                + (Debug.getNativeHeapAllocatedSize() >> 20) + " MiB");
        assertTrue(errors.toString(), errors.isEmpty());
        assertTrue("held " + sampler.most.get(), sampler.most.get() <= DecodeMemory.MAX_BYTES);
        DecodeGate.resetForTest();
        final long until = SystemClock.uptimeMillis() + 6000;
        while (DecodeMemory.held() > ledgerBefore && SystemClock.uptimeMillis() < until) {
            System.gc();
            SystemClock.sleep(50);
        }
        assertTrue("own live storage remained charged: " + DecodeMemory.held(), DecodeMemory.held() <= ledgerBefore);
        assertTrue("nothing made", made.get() > 0);
    }
}
