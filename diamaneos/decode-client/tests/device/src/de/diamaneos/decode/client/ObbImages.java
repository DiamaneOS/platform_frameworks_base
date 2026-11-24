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

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.storage.OnObbStateChangeListener;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * OBB files for the OBB device tests: a small FAT12 image with one file, or a blank image, each with the OBB footer
 * that names this package (ObbFile.cpp's version 1 layout). Also a listener that keeps every answer and the count of
 * OBB volumes the system knows.
 */
final class ObbImages {
    static final String TEXT = "hello obb\n";
    static final String FILE_NAME = "HELLO.TXT";

    private static final int SECTOR = 512;
    private static final int SECTORS = 2048;
    private static final int FAT_SECTORS = 6;

    private ObbImages() {
    }

    /** Writes an OBB of this package to `file`: a FAT12 image holding HELLO.TXT, or a blank one that cannot mount. */
    static File write(Context context, File file, boolean fat) throws IOException {
        final ByteBuffer image = ByteBuffer.allocate(SECTOR * SECTORS).order(ByteOrder.LITTLE_ENDIAN);
        if (fat) fillFat12(image);
        final byte[] name = context.getPackageName().getBytes(StandardCharsets.UTF_8);
        final ByteBuffer footer = ByteBuffer.allocate(32 + name.length).order(ByteOrder.LITTLE_ENDIAN);
        footer.putInt(1);  // signature version
        footer.putInt(1);  // package version
        footer.putInt(0);  // flags
        footer.putLong(0);  // salt
        footer.putInt(name.length);
        footer.put(name);
        footer.putInt(24 + name.length);  // footer size
        footer.putInt(0x01059983);  // footer marker
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(image.array());
            out.write(footer.array());
            out.getFD().sync();
        }
        return file;
    }

    private static void fillFat12(ByteBuffer b) {
        final int rootSector = 1 + 2 * FAT_SECTORS;
        final int dataSector = rootSector + 1;
        b.put(0, (byte) 0xEB).put(1, (byte) 0x3C).put(2, (byte) 0x90);
        b.position(3);
        b.put("MSDOS5.0".getBytes(StandardCharsets.US_ASCII));
        b.putShort(11, (short) SECTOR);
        b.put(13, (byte) 1);  // sectors per cluster
        b.putShort(14, (short) 1);  // reserved sectors
        b.put(16, (byte) 2);  // FATs
        b.putShort(17, (short) 16);  // root entries: one sector
        b.putShort(19, (short) SECTORS);
        b.put(21, (byte) 0xF8);
        b.putShort(22, (short) FAT_SECTORS);
        b.putShort(24, (short) 32);
        b.putShort(26, (short) 2);
        b.put(36, (byte) 0x80);
        b.put(38, (byte) 0x29);
        b.putInt(39, 0x0BB70BB7);
        b.position(43);
        b.put("OBBTEST    ".getBytes(StandardCharsets.US_ASCII));
        b.put("FAT12   ".getBytes(StandardCharsets.US_ASCII));
        b.put(510, (byte) 0x55).put(511, (byte) 0xAA);
        // Both FATs: the media entry, the end entry, and cluster 2 (the file) at its end.
        for (int f = 0; f < 2; f++) {
            final int at = (1 + f * FAT_SECTORS) * SECTOR;
            b.put(at, (byte) 0xF8).put(at + 1, (byte) 0xFF).put(at + 2, (byte) 0xFF);
            b.put(at + 3, (byte) 0xFF).put(at + 4, (byte) 0x0F);
        }
        final int root = rootSector * SECTOR;
        b.position(root);
        b.put("OBBTEST    ".getBytes(StandardCharsets.US_ASCII));
        b.put(root + 11, (byte) 0x08);  // volume label
        b.position(root + 32);
        b.put("HELLO   TXT".getBytes(StandardCharsets.US_ASCII));
        b.put(root + 32 + 11, (byte) 0x20);  // archive
        b.putShort(root + 32 + 26, (short) 2);
        b.putInt(root + 32 + 28, TEXT.length());
        b.position(dataSector * SECTOR);
        b.put(TEXT.getBytes(StandardCharsets.US_ASCII));
    }

    /** The text of HELLO.TXT in a mounted image's folder, whatever case the VM shows its name in. */
    static String readFile(File dir) throws IOException {
        final File[] files = dir.listFiles();
        assertTrue("cannot list " + dir, files != null);
        for (File f : files) {
            if (f.getName().equalsIgnoreCase(FILE_NAME)) {
                return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII);
            }
        }
        throw new IOException(FILE_NAME + " not in " + dir);
    }

    /** How many OBB volumes the system server knows now (each one vold holds is listed). */
    static int obbVolumes(StorageManager storage) {
        int n = 0;
        for (VolumeInfo vol : storage.getVolumes()) {
            if (vol.getType() == VolumeInfo.TYPE_OBB) n++;
        }
        return n;
    }

    /** Every answer the system gives a listener, in order. */
    static final class Answers extends OnObbStateChangeListener {
        final LinkedBlockingQueue<Integer> states = new LinkedBlockingQueue<>();
        final List<Integer> all = new ArrayList<>();

        @Override
        public void onObbStateChange(String path, int state) {
            synchronized (all) {
                all.add(state);
            }
            states.add(state);
        }

        /** The next answer within `seconds`, or -1. */
        int next(int seconds) throws InterruptedException {
            final Integer s = states.poll(seconds, TimeUnit.SECONDS);
            return s == null ? -1 : s;
        }

        int count(int state) {
            synchronized (all) {
                int n = 0;
                for (int s : all) {
                    if (s == state) n++;
                }
                return n;
            }
        }
    }

    /** Mounts `file` and returns its listener once the system said MOUNTED. */
    static Answers mount(StorageManager storage, File file) throws InterruptedException {
        final Answers answers = new Answers();
        assertTrue(storage.mountObb(file.getPath(), null, answers));
        final int state = answers.next(60);
        assertTrue("mount of " + file + " answered " + state, state == OnObbStateChangeListener.MOUNTED);
        return answers;
    }
}
