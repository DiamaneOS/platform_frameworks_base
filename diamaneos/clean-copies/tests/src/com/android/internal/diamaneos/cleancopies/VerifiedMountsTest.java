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

package com.android.internal.diamaneos.cleancopies;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The mount table rule of verified system files, on mount tables as a phone shows them and as tricks make them. */
public class VerifiedMountsTest {
    // A phone's table: system as root, system_ext and product on verity devices, data writable on its own device.
    private static final String PHONE = String.join("\n",
            "1 0 253:9 / / ro,relatime master:1 - erofs /dev/block/dm-9 ro,seclabel",
            "30 1 253:15 / /system_ext ro,relatime shared:5 - ext4 /dev/block/dm-15 ro,seclabel",
            "31 1 253:16 / /product ro,relatime shared:6 - ext4 /dev/block/dm-16 ro,seclabel",
            "32 1 253:17 / /vendor ro,relatime shared:7 - erofs /dev/block/dm-17 ro,seclabel",
            "33 1 253:18 / /odm ro,relatime shared:8 - erofs /dev/block/dm-18 ro,seclabel",
            "60 1 253:40 / /data rw,nosuid,nodev,noatime shared:30 - f2fs /dev/block/dm-40 rw,seclabel",
            "61 1 7:24 / /apex/com.android.art@1 ro,nodev,noatime shared:31 - ext4 /dev/block/loop24 ro",
            "62 1 0:22 / /proc rw,nosuid,nodev,noexec,relatime shared:9 - proc proc rw",
            "");

    @Test
    public void systemPartitionsAreVerified() {
        assertTrue(VerifiedMounts.verifiedDevice(PHONE, 253, 9));
        assertTrue(VerifiedMounts.verifiedDevice(PHONE, 253, 15));
        assertTrue(VerifiedMounts.verifiedDevice(PHONE, 253, 16));
        assertTrue(VerifiedMounts.verifiedDevice(PHONE, 253, 17));
        assertTrue(VerifiedMounts.verifiedDevice(PHONE, 253, 18));
    }

    @Test
    public void dataApexProcAndUnknownDevicesAreNot() {
        assertFalse(VerifiedMounts.verifiedDevice(PHONE, 253, 40));
        assertFalse(VerifiedMounts.verifiedDevice(PHONE, 7, 24));
        assertFalse(VerifiedMounts.verifiedDevice(PHONE, 0, 22));
        assertFalse(VerifiedMounts.verifiedDevice(PHONE, 253, 99));
        // A memory file or a pipe lives on an anonymous device that no partition mount names.
        assertFalse(VerifiedMounts.verifiedDevice(PHONE, 0, 1));
    }

    @Test
    public void dataBindMountedOverASystemPathIsNot() {
        // A file of /data bind mounted at /system/etc/x.png and read-only there: still the data device, writable at
        // /data, and the bind's mount point is not a partition's.
        final String table = PHONE
                + "70 1 253:40 /media/x.png /system/etc/x.png ro,relatime - f2fs /dev/block/dm-40 rw,seclabel\n";
        assertFalse(VerifiedMounts.verifiedDevice(table, 253, 40));
    }

    @Test
    public void dataDeviceMountedReadOnlyAtAPartitionPointIsNot() {
        // The data device mounted read-only over /product as well: it is mounted writable at /data.
        final String table = PHONE
                + "71 1 253:40 / /product ro,relatime - f2fs /dev/block/dm-40 ro,seclabel\n";
        assertFalse(VerifiedMounts.verifiedDevice(table, 253, 40));
    }

    @Test
    public void systemBindMountedElsewhereStaysVerified() {
        // A bind of a system directory somewhere else (as the platform binds /system/etc parts): the same verified
        // device, read-only everywhere.
        final String table = PHONE
                + "72 1 253:9 /system/etc /linkerconfig/etc ro,relatime - erofs /dev/block/dm-9 ro,seclabel\n";
        assertTrue(VerifiedMounts.verifiedDevice(table, 253, 9));
    }

    @Test
    public void writableMountOfASystemDeviceIsNot() {
        final String table = PHONE
                + "73 1 253:16 / /mnt/rw-product rw,relatime - ext4 /dev/block/dm-16 rw,seclabel\n";
        assertFalse(VerifiedMounts.verifiedDevice(table, 253, 16));
    }

    @Test
    public void remountOverlayIsNot() {
        // adb remount: an overlay over /system_ext on its own anonymous device.
        final String table = PHONE
                + "74 30 0:45 / /system_ext ro,relatime - overlay overlay ro,lowerdir=/system_ext\n";
        assertFalse(VerifiedMounts.verifiedDevice(table, 0, 45));
    }

    @Test
    public void notADeviceMapperDeviceIsNot() {
        final String table = "1 0 8:3 / / ro,relatime - ext4 /dev/block/sda3 ro\n";
        assertFalse(VerifiedMounts.verifiedDevice(table, 8, 3));
    }

    @Test
    public void tmpfsAtAPartitionPointIsNot() {
        final String table = "1 0 0:30 / /vendor ro,relatime - tmpfs tmpfs ro\n";
        assertFalse(VerifiedMounts.verifiedDevice(table, 0, 30));
    }

    @Test
    public void escapedMountPointsAndBrokenLines() {
        final String table = "1 0 253:5 / /prod\\040uct ro,relatime - ext4 /dev/block/dm-5 ro\n"
                + "2 0 253:6 / /product ro\n";
        assertFalse(VerifiedMounts.verifiedDevice(table, 253, 5)); // "/prod uct" is not /product
        assertFalse(VerifiedMounts.verifiedDevice(table, 253, 6)); // a line without its fields
    }

    @Test
    public void aLineWithoutSuperOptionsIsNot() {
        // Ten fields, the separator and the source but no super options: read as unknown, never as verified.
        final String table = "1 0 253:7 / /system ro,relatime shared:1 - ext4 /dev/block/dm-7\n";
        assertFalse(VerifiedMounts.verifiedDevice(table, 253, 7));
    }

    @Test
    public void deviceNumbers() {
        // makedev(253, 16) as the kernel packs it, and a minor above 255.
        assertEquals(253, VerifiedMounts.major(0xfd10L));
        assertEquals(16, VerifiedMounts.minor(0xfd10L));
        final long dev = (300 & 0xff) | (253L << 8) | ((long) (300 & ~0xff) << 12);
        assertEquals(253, VerifiedMounts.major(dev));
        assertEquals(300, VerifiedMounts.minor(dev));
    }
}
