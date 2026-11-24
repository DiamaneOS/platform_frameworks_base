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

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import android.content.Context;
import android.os.Parcel;
import android.os.storage.DiskInfo;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.os.storage.VolumeActions;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.android.server.storage.ObbRecords;
import com.android.server.storage.ObbCleanup;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public class StorageRereviewDeviceTest {
    @Test public void waitingOffersMountAndStaleWizardsCannotOfferErase() {
        DiskInfo disk = new DiskInfo("disk:179,0", DiskInfo.FLAG_SD);
        disk.size = disk.mediumBytes = 4096;
        disk.mediumGeneration = "41";
        VolumeInfo vol = new VolumeInfo("public:179,1", VolumeInfo.TYPE_PUBLIC, disk, null);
        vol.state = VolumeInfo.STATE_MOUNTED;
        assertTrue(VolumeActions.canFormatDisk(disk, List.of(vol)));
        for (int state : new int[] {VolumeInfo.STATE_NOT_WRITTEN_BACK, VolumeInfo.STATE_UNOPENABLE,
                VolumeInfo.STATE_MOUNTED_READ_ONLY, VolumeInfo.STATE_WAITING_FOR_PLACE,
                VolumeInfo.STATE_CHECKING}) {
            vol.state = state;
            assertFalse("erase in " + state, VolumeActions.canFormatDisk(disk, List.of(vol)));
        }
        vol.state = VolumeInfo.STATE_WAITING_FOR_PLACE;
        assertTrue(VolumeActions.allows(vol, VolumeActions.RETRY));
        Parcel parcel = Parcel.obtain();
        try {
            disk.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            DiskInfo copy = DiskInfo.CREATOR.createFromParcel(parcel);
            assertEquals("41", copy.mediumGeneration);
            assertEquals(4096, copy.mediumBytes);
        } finally { parcel.recycle(); }
    }

    private static final class Record implements ObbRecords.Record {
        final String path;
        Record(String path) { this.path = path; }
        public int ownerUid() { return 10100; }
        public String canonicalPath() { return path; }
        public String volId() { return path; }
    }

    @Test public void ejectMatchesWholeComponents() {
        ObbRecords<Record> records = new ObbRecords<>();
        Record a = new Record("/mnt/media_rw/public:8,1/a.obb");
        Record b = new Record("/mnt/media_rw/public:8,10/b.obb");
        assertTrue(records.add(a));
        assertTrue(records.add(b));
        assertEquals(List.of(a), records.below("/mnt/media_rw/public:8,1"));
        assertEquals(List.of(a), records.below("/mnt/media_rw/public:8,1/"));
    }

    @Test public void refusedCleanupKeepsTheNativeIdAndReportsNoSuccess() {
        ObbRecords<Record> records = new ObbRecords<>();
        Record record = new Record("/data/media/0/Android/obb/a.obb");
        records.add(record);
        AtomicInteger ends = new AtomicInteger();
        Runnable confirmed = () -> { records.remove(record); ends.incrementAndGet(); };
        assertFalse(ObbCleanup.end(id -> { throw new android.os.RemoteException("injected"); },
                record.volId(), confirmed));
        assertSame(record, records.findByVolId(record.volId()));
        assertEquals(0, ends.get());
        assertTrue(ObbCleanup.end(id -> {}, record.volId(), confirmed));
        assertNull(records.findByVolId(record.volId()));
        assertEquals(1, ends.get());
        ObbCleanup.Pending<Record> pending = new ObbCleanup.Pending<>();
        java.util.ArrayList<Runnable> turns = new java.util.ArrayList<>();
        AtomicInteger nativeCalls = new AtomicInteger();
        AtomicInteger confirmations = new AtomicInteger();
        pending.retry(record, id -> {
            if (nativeCalls.incrementAndGet() == 1) throw new android.os.RemoteException("rollback refused");
        }, (work, millis) -> turns.add(work), state -> confirmations.incrementAndGet());
        turns.remove(0).run();
        assertEquals(1, pending.size());
        assertSame(record, pending.valueAt(0));
        assertEquals(0, confirmations.get());
        turns.remove(0).run();
        assertEquals(0, pending.size());
        assertEquals(2, nativeCalls.get());
        assertEquals(1, confirmations.get());
    }

    @Test public void nativeRefusalAndTimeoutNeverForgetTheAdoptedKey() {
        android.os.storage.DiskInfo disk = new android.os.storage.DiskInfo("disk:179,0", DiskInfo.FLAG_SD);
        disk.size = disk.mediumBytes = 4096;
        disk.mediumGeneration = "41";
        VolumeInfo adopted = new VolumeInfo("private:179,3", VolumeInfo.TYPE_PRIVATE, disk, "guid");
        adopted.state = VolumeInfo.STATE_MOUNTED;
        adopted.fsUuid = "saved-uuid";
        var view = new java.util.concurrent.atomic.AtomicReference<>(
                new com.android.server.storage.ConfirmedPartition.View(disk, List.of(adopted)));
        AtomicInteger forgotten = new AtomicInteger();
        for (Exception failure : List.of(new android.os.RemoteException("native refusal"),
                new java.util.concurrent.TimeoutException("scan did not complete"))) {
            try {
                com.android.server.storage.ConfirmedPartition.run(disk.id, "41", 4096, "saved-uuid",
                        view::get, () -> { throw failure; }, uuid -> forgotten.incrementAndGet());
                fail("reported completion after " + failure);
            } catch (IllegalStateException expected) { }
            assertEquals(0, forgotten.get());
        }
        VolumeInfo portable = new VolumeInfo("public:179,1", VolumeInfo.TYPE_PUBLIC, disk, null);
        portable.state = VolumeInfo.STATE_UNMOUNTED;
        com.android.server.storage.ConfirmedPartition.run(disk.id, "41", 4096, "saved-uuid", view::get,
                () -> view.set(new com.android.server.storage.ConfirmedPartition.View(disk, List.of(portable))),
                uuid -> forgotten.incrementAndGet());
        assertEquals(0, forgotten.get());
    }

    @Test public void resetCanEraseAnUnverifiedVolumeOnTheSameAdoptableMedium() {
        DiskInfo selected = new DiskInfo("disk:179,0", DiskInfo.FLAG_ADOPTABLE | DiskInfo.FLAG_SD);
        selected.size = selected.mediumBytes = 4096;
        selected.mediumGeneration = "41";
        VolumeInfo volume = new VolumeInfo("public:179,1", VolumeInfo.TYPE_PUBLIC, selected, null);
        volume.state = VolumeInfo.STATE_FORMATTED_UNVERIFIED;
        assertFalse(VolumeActions.canFormatDisk(selected, List.of(volume)));
        var current = new com.android.server.storage.ConfirmedPartition.View(
                selected.clone(), List.of(volume));
        AtomicInteger calls = new AtomicInteger();
        com.android.server.storage.ConfirmedPartition.runForReset(selected, current,
                () -> calls.incrementAndGet());
        assertEquals(1, calls.get());

        current.disk().mediumGeneration = "42";
        try {
            com.android.server.storage.ConfirmedPartition.runForReset(selected, current,
                    () -> calls.incrementAndGet());
            fail("The replacement medium was eligible for the stale reset erase");
        } catch (IllegalStateException expected) { }
        assertEquals(1, calls.get());
    }

    @Test public void changedConfirmationIsRefusedWithoutTouchingTheDisk() {
        StorageManager storage = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSystemService(StorageManager.class);
        DiskInfo disk = null;
        for (DiskInfo candidate : storage.getDisks()) if (candidate.isSd()) disk = candidate;
        assumeTrue("needs SD card", disk != null);
        String id = disk.id;
        List<String> before = storage.getVolumes().stream().filter(v -> id.equals(v.getDiskId()))
                .map(v -> v.id + ":" + v.state + ":" + v.fsUuid).toList();
        try {
            storage.partitionPublicConfirmed(id, disk.mediumGeneration + "1", disk.mediumBytes, null);
            fail("accepted another generation");
        } catch (IllegalStateException expected) { }
        List<String> after = storage.getVolumes().stream().filter(v -> id.equals(v.getDiskId()))
                .map(v -> v.id + ":" + v.state + ":" + v.fsUuid).toList();
        assertEquals(before, after);
    }
    @Test public void adoptedConversionRefusalPreservesTheRecord() {
        String uuid = InstrumentationRegistry.getArguments().getString("adopted_uuid");
        assumeTrue("needs an adopted-card refusal fixture", uuid != null);
        StorageManager storage = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSystemService(StorageManager.class);
        VolumeInfo adopted = storage.findVolumeByUuid(uuid);
        assertNotNull(adopted);
        DiskInfo disk = adopted.getDisk();
        boolean refused = false;
        try {
            storage.partitionPublicConfirmed(disk.id, disk.mediumGeneration, disk.mediumBytes, uuid);
        } catch (IllegalStateException expected) {
            refused = true;
        }
        assertTrue("conversion was reported successful after the native refusal", refused);
        assertNotNull("the adopted record was forgotten", storage.findRecordByUuid(uuid));
    }
    @Test public void waitingAndFormatterReportsHaveNoForgetAuthority() {
        DiskInfo disk = new DiskInfo("disk:179,0", DiskInfo.FLAG_SD);
        disk.size = disk.mediumBytes = 4096;
        disk.mediumGeneration = "41";
        VolumeInfo adopted = new VolumeInfo("private:179,3", VolumeInfo.TYPE_PRIVATE, disk, "guid-b");
        adopted.fsUuid = "duplicate-uuid";
        adopted.state = VolumeInfo.STATE_MOUNTED;
        for (int state : new int[] {VolumeInfo.STATE_WAITING_FOR_PLACE, VolumeInfo.STATE_UNMOUNTED,
                VolumeInfo.STATE_FORMATTED_UNVERIFIED}) {
            var view = new java.util.concurrent.atomic.AtomicReference<>(
                    new com.android.server.storage.ConfirmedPartition.View(disk, List.of(adopted)));
            AtomicInteger forgotten = new AtomicInteger();
            VolumeInfo reply = new VolumeInfo("public:179,1", VolumeInfo.TYPE_PUBLIC, disk, null);
            reply.state = state;
            try {
                com.android.server.storage.ConfirmedPartition.run(disk.id, "41", 4096,
                        "duplicate-uuid", view::get,
                        () -> view.set(new com.android.server.storage.ConfirmedPartition.View(disk, List.of(reply))),
                        ignored -> forgotten.incrementAndGet());
            } catch (android.os.ServiceSpecificException expected) {
                assertEquals(4001, expected.errorCode);
            }
            assertEquals(0, forgotten.get());
        }
    }

    @Test public void duplicateUuidDoesNotSelectAnotherPartitionKey() {
        DiskInfo disk = new DiskInfo("disk:179,0", DiskInfo.FLAG_SD);
        disk.sysPath = "/devices/card-b";
        VolumeInfo volume = new VolumeInfo("private:179,3", VolumeInfo.TYPE_PRIVATE, disk, "guid-b");
        volume.fsUuid = "duplicate-uuid";
        android.os.storage.VolumeRecord record = new android.os.storage.VolumeRecord(
                VolumeInfo.TYPE_PRIVATE, "duplicate-uuid");
        record.partGuid = "guid-a";
        record.diskSysPath = "/devices/card-a";
        assertFalse(android.os.storage.AdoptedVolumeIdentity.matches(record, volume));
        try {
            android.os.storage.AdoptedVolumeIdentity.checkForget(record, List.of(volume));
            fail("UUID-only Forget selected another partition");
        } catch (IllegalStateException expected) { }
        record.partGuid = "guid-b";
        assertFalse(android.os.storage.AdoptedVolumeIdentity.matches(record, volume));
        record.diskSysPath = disk.sysPath;
        assertTrue(android.os.storage.AdoptedVolumeIdentity.matches(record, volume));
    }

    @Test public void legacyRecordCanBeForgottenOnlyWithoutALiveCollision() {
        var record = new android.os.storage.VolumeRecord(VolumeInfo.TYPE_PRIVATE, "legacy-uuid");
        android.os.storage.AdoptedVolumeIdentity.checkForget(record, List.of());

        DiskInfo disk = new DiskInfo("disk:179,0", DiskInfo.FLAG_SD);
        VolumeInfo returned = new VolumeInfo("private:179,3", VolumeInfo.TYPE_PRIVATE, disk, "ab");
        returned.fsUuid = record.fsUuid;
        try {
            android.os.storage.AdoptedVolumeIdentity.checkForget(record, List.of(returned));
            fail("Forget passed while the UUID was live");
        } catch (IllegalStateException expected) { }
    }

    @Test public void freshConfirmationCanOfferAnotherEraseAfterFormatAwaitingVerification() {
        DiskInfo disk = new DiskInfo("disk:179,0", DiskInfo.FLAG_SD);
        disk.size = disk.mediumBytes = 4096;
        disk.mediumGeneration = "41";
        VolumeInfo volume = new VolumeInfo("public:179,1", VolumeInfo.TYPE_PUBLIC, disk, null);
        volume.state = VolumeInfo.STATE_FORMATTED_UNVERIFIED;
        assertFalse(android.os.storage.VolumeActions.allows(volume, android.os.storage.VolumeActions.FORMAT));
        assertFalse(android.os.storage.VolumeActions.canFormatDisk(disk, List.of(volume)));
        assertTrue(android.os.storage.VolumeActions.canFormatDisk(disk, List.of(volume), true));
        assertTrue(android.os.storage.VolumeActions.allows(volume, android.os.storage.VolumeActions.RETRY));
        assertTrue(volume.getStateDescription() != 0);
    }

    @Test public void aSurvivingAliasUsesTheSameNormalizedPartitionKey() {
        var survivor = new android.os.storage.VolumeRecord(VolumeInfo.TYPE_PRIVATE, "survivor");
        survivor.partGuid = "AB:cd-ef";
        assertTrue(android.os.storage.AdoptedVolumeIdentity.partitionKeyInUse(
                "ab-cd:EF", List.of(survivor)));
        assertFalse(android.os.storage.AdoptedVolumeIdentity.partitionKeyInUse(
                "ab-cd:EF", List.of()));
    }

    @Test public void adoptedInPlaceFormatKeepsTheSavedRecord() {
        String uuid = InstrumentationRegistry.getArguments().getString("format_adopted_uuid");
        String guid = InstrumentationRegistry.getArguments().getString("format_adopted_guid");
        assumeTrue("needs the explicitly selected erasable adopted-card fixture", uuid != null && guid != null);
        StorageManager storage = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSystemService(StorageManager.class);
        VolumeInfo volume = storage.getVolumes().stream().filter(v ->
                v.type == VolumeInfo.TYPE_PRIVATE && "disk:179,0".equals(v.getDiskId())
                && uuid.equals(v.fsUuid) && guid.equalsIgnoreCase(v.partGuid)).findFirst().orElse(null);
        assertNotNull(volume);
        assertNotNull(storage.findRecordByUuid(uuid));
        assertTrue(guid.equalsIgnoreCase(storage.findRecordByUuid(uuid).partGuid));
        storage.format(volume.id);
        assertNotNull("in-place format automatically forgot the saved record", storage.findRecordByUuid(uuid));
    }

    @Test public void realAdoptedWaitingReportKeepsTheSavedRecord() throws Exception {
        String uuid = InstrumentationRegistry.getArguments().getString("waiting_conversion_uuid");
        String token = InstrumentationRegistry.getArguments().getString("waiting_conversion_token");
        String guid = InstrumentationRegistry.getArguments().getString("waiting_conversion_guid");
        assumeTrue("needs the selected erasable adopted-card placement fixture",
                uuid != null && token != null && guid != null);
        assertTrue(token.matches("[a-zA-Z0-9-]+"));
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        StorageManager storage = context.getSystemService(StorageManager.class);
        VolumeInfo adopted = storage.getVolumes().stream().filter(v ->
                v.type == VolumeInfo.TYPE_PRIVATE && "disk:179,0".equals(v.getDiskId())
                && uuid.equals(v.fsUuid) && guid.equalsIgnoreCase(v.partGuid)).findFirst().orElse(null);
        assertNotNull(adopted);
        assertNotNull(storage.findRecordByUuid(uuid));
        assertTrue(guid.equalsIgnoreCase(storage.findRecordByUuid(uuid).partGuid));
        DiskInfo disk = adopted.getDisk().clone();
        assertTrue(disk.isSd());
        String diskId = disk.id;
        var before = new com.android.server.storage.ConfirmedPartition.View(disk,
                storage.getVolumes());
        AtomicInteger snapshots = new AtomicInteger();
        java.io.File folder = new java.io.File(context.createDeviceProtectedStorageContext().getCacheDir(),
                "sol1brr-b1-" + token);
        assertTrue(folder.mkdirs() || folder.isDirectory());
        java.io.File ready = new java.io.File(folder, "ready");
        java.io.File go = new java.io.File(folder, "go");
        try {
            java.nio.file.Files.write(go.toPath(), "pending".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            com.android.server.storage.ConfirmedPartition.run(diskId, disk.mediumGeneration,
                    disk.mediumBytes, uuid, () -> {
                        if (snapshots.getAndIncrement() == 0) return before;
                        return new com.android.server.storage.ConfirmedPartition.View(
                                storage.findDiskById(diskId), storage.getVolumes());
                    }, () -> {
                        java.nio.file.Files.write(ready.toPath(), token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        long until = android.os.SystemClock.elapsedRealtime() + 180000;
                        while (!"done".equals(new String(java.nio.file.Files.readAllBytes(go.toPath()),
                                java.nio.charset.StandardCharsets.UTF_8))) {
                            if (android.os.SystemClock.elapsedRealtime() >= until) {
                                throw new java.util.concurrent.TimeoutException("waiting-card fixture did not finish");
                            }
                            Thread.sleep(250);
                        }
                    }, storage::forgetVolume);
            assertNotNull("a waiting scan automatically forgot the real adopted record",
                    storage.findRecordByUuid(uuid));
        } finally {
            java.nio.file.Files.deleteIfExists(ready.toPath());
            java.nio.file.Files.deleteIfExists(go.toPath());
            java.nio.file.Files.deleteIfExists(folder.toPath());
        }
    }

    @Test public void openTheStockForgetPageForTheSelectedRecord() {
        String uuid = InstrumentationRegistry.getArguments().getString("open_forget_uuid");
        String guid = InstrumentationRegistry.getArguments().getString("open_forget_guid");
        assumeTrue("needs the selected erasable adopted-card Forget fixture", uuid != null && guid != null);
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        StorageManager storage = context.getSystemService(StorageManager.class);
        android.os.storage.VolumeRecord record = storage.findRecordByUuid(uuid);
        assertNotNull(record);
        assertEquals(guid.toLowerCase(java.util.Locale.ROOT),
                record.partGuid.toLowerCase(java.util.Locale.ROOT));
        android.os.Bundle arguments = new android.os.Bundle();
        arguments.putString(android.os.storage.VolumeRecord.EXTRA_FS_UUID, uuid);
        android.content.Intent intent = new android.content.Intent();
        intent.setClassName("com.android.settings", "com.android.settings.SubSettings");
        intent.putExtra(":settings:show_fragment", "com.android.settings.deviceinfo.PrivateVolumeForget");
        intent.putExtra(":settings:show_fragment_args", arguments);
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        android.app.UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        automation.adoptShellPermissionIdentity("android.permission.START_ANY_ACTIVITY",
                "android.permission.START_ACTIVITIES_FROM_BACKGROUND");
        try {
            context.startActivity(intent);
        } finally {
            automation.dropShellPermissionIdentity();
        }
    }

}
