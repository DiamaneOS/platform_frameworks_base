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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.os.storage.DiskInfo;
import android.os.storage.StorageManager;
import android.os.storage.VolumeActions;
import android.os.storage.VolumeInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * What the phone offers for cards: no new adoptable storage, no format offer for an adopted card whose key is missing,
 * and the actions of each card state, the same for the storage page and the notices (VolumeActions).
 */
@RunWith(AndroidJUnit4.class)
public class CardDecisionsDeviceTest {
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final StorageManager mStorage = mContext.getSystemService(StorageManager.class);

    @Test
    public void newAdoptableStorageIsRefusedBeforeTheCardIsTouched() {
        assertFalse(VolumeActions.newAdoptableStorage());
        final DiskInfo sd = sdDisk();
        assumeTrue("needs an SD card", sd != null);
        final List<String> before = volumeStates(sd);
        try {
            mStorage.partitionPrivate(sd.getId());
            fail("partitionPrivate was accepted");
        } catch (UnsupportedOperationException expected) {
        }
        try {
            mStorage.partitionMixed(sd.getId(), 50);
            fail("partitionMixed was accepted");
        } catch (UnsupportedOperationException expected) {
        }
        // Refused before vold was asked: the card's volumes are the same ones in the same states.
        assertEquals(before, volumeStates(sd));
    }

    @Test
    public void anAdoptedCardWithoutItsKeyIsNotOfferedForFormatting() {
        // Only on a phone set up for it: a card adopted before, with its key moved away (the host does that).
        assumeTrue("needs keyless_card=1",
                "1".equals(InstrumentationRegistry.getArguments().getString("keyless_card")));
        final DiskInfo sd = sdDisk();
        assertNotNull("no SD card", sd);
        assertFalse("a format is offered for the adopted card", sd.isFormatOffered());
        final List<VolumeInfo> vols = volumesOf(sd);
        assertFalse("the disk has no volume, so it looks blank", vols.isEmpty());
        for (VolumeInfo vol : vols) {
            assertEquals(VolumeInfo.STATE_UNOPENABLE, vol.getState());
            assertFalse(VolumeActions.allows(vol, VolumeActions.FORMAT));
        }
    }

    @Test
    public void eachCardStateHasItsActions() {
        final DiskInfo disk = new DiskInfo("disk:179,0", DiskInfo.FLAG_SD | DiskInfo.FLAG_ADOPTABLE);
        final VolumeInfo vol = new VolumeInfo("public:179,1", VolumeInfo.TYPE_PUBLIC, disk, null);
        final int all = VolumeActions.RETRY | VolumeActions.BROWSE | VolumeActions.EJECT
                | VolumeActions.FORMAT;

        vol.state = VolumeInfo.STATE_MOUNTED;
        assertEquals(VolumeActions.BROWSE | VolumeActions.EJECT | VolumeActions.FORMAT,
                VolumeActions.forPublic(vol));
        assertEquals(com.android.internal.R.string.ext_media_status_mounted, VolumeActions.statusText(vol));

        // Read-only, being checked or for good: Explore and Eject, never Format, and its own status while checked.
        vol.state = VolumeInfo.STATE_MOUNTED_READ_ONLY;
        assertEquals(VolumeActions.BROWSE | VolumeActions.EJECT, VolumeActions.forPublic(vol));
        assertEquals(com.android.internal.R.string.ext_media_status_mounted_ro, VolumeActions.statusText(vol));
        vol.checkPending = true;
        assertEquals(VolumeActions.BROWSE | VolumeActions.EJECT, VolumeActions.forPublic(vol));
        assertEquals(com.android.internal.R.string.ext_media_status_checking, VolumeActions.statusText(vol));
        assertTrue(VolumeActions.showsPage(vol.state));
        vol.checkPending = false;

        // Could not be opened, not written back: the page stays open, with Retry only.
        for (int state : new int[] {VolumeInfo.STATE_UNOPENABLE, VolumeInfo.STATE_NOT_WRITTEN_BACK}) {
            vol.state = state;
            assertEquals(VolumeActions.RETRY, VolumeActions.forPublic(vol) & all);
            assertTrue(VolumeActions.isRecoverable(state));
            assertTrue(VolumeActions.showsPage(state));
        }
        // Waiting for a VM place: mounts by itself, the page stays open and offers nothing.
        vol.state = VolumeInfo.STATE_WAITING_FOR_PLACE;
        assertEquals(0, VolumeActions.forPublic(vol));
        assertTrue(VolumeActions.showsPage(vol.state));
        // A retry goes through checking: the page stays open.
        assertTrue(VolumeActions.showsPage(VolumeInfo.STATE_CHECKING));
        // Stock: an ejected volume's page closes, a removed one offers nothing.
        assertFalse(VolumeActions.showsPage(VolumeInfo.STATE_UNMOUNTED));
        vol.state = VolumeInfo.STATE_REMOVED;
        assertEquals(0, VolumeActions.forPublic(vol));
    }

    private DiskInfo sdDisk() {
        for (DiskInfo disk : mStorage.getDisks()) {
            if (disk.isSd()) return disk;
        }
        return null;
    }

    private List<VolumeInfo> volumesOf(DiskInfo disk) {
        final List<VolumeInfo> found = new ArrayList<>();
        for (VolumeInfo vol : mStorage.getVolumes()) {
            if (disk.getId().equals(vol.getDiskId())) found.add(vol);
        }
        return found;
    }

    private List<String> volumeStates(DiskInfo disk) {
        final List<String> states = new ArrayList<>();
        for (VolumeInfo vol : volumesOf(disk)) states.add(vol.getId() + "=" + vol.getState());
        return states;
    }
}
