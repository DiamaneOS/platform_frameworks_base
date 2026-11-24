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
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.os.SystemClock;
import android.os.storage.OnObbStateChangeListener;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * OBB records stay consistent with what vold serves. A failed mount leaves no volume behind, in vold or in the system
 * server. An image whose VM ends without the app asking (a card's drive took its place, or its fsvmd ended) is
 * dropped once: its app hears UNMOUNTED once, the image is not reported mounted, and it mounts again cleanly.
 *
 * <p>The end is caused by the instrumentation argument obb_end: "card" ejects the SD card, mounts two images, and
 * mounts the card again, so the card's drive VM takes the place of the newest image if there is no room for both.
 * "kill" waits up to two minutes for the host to end the image's fsvmd (it logs OBB-READY with the image's volume).
 * Without the argument the end test is skipped.
 */
@RunWith(AndroidJUnit4.class)
public class ObbLifecycleDeviceTest {
    private static final String TAG = "ObbLifecycleDeviceTest";

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final StorageManager mStorage = mContext.getSystemService(StorageManager.class);
    private final List<File> mMounted = new ArrayList<>();
    private VolumeInfo mCardToMount;

    @After
    public void tearDown() throws Exception {
        for (File f : mMounted) {
            if (mStorage.isObbMounted(f.getPath())) {
                final ObbImages.Answers answers = new ObbImages.Answers();
                mStorage.unmountObb(f.getPath(), true, answers);
                answers.next(30);
            }
            f.delete();
        }
        if (mCardToMount != null) mStorage.mount(mCardToMount.getId());
    }

    private File obb(String name, boolean fat) throws Exception {
        final File f = ObbImages.write(mContext, new File(mContext.getObbDir(), name), fat);
        mMounted.add(f);
        return f;
    }

    @Test
    public void failedMountsLeaveNoVolumeBehind() throws Exception {
        final File bad = obb("blank.obb", false);
        final int before = ObbImages.obbVolumes(mStorage);
        for (int i = 0; i < 3; i++) {
            final ObbImages.Answers answers = new ObbImages.Answers();
            assertTrue(mStorage.mountObb(bad.getPath(), null, answers));
            assertEquals(OnObbStateChangeListener.ERROR_COULD_NOT_MOUNT, answers.next(60));
            assertFalse(mStorage.isObbMounted(bad.getPath()));
        }
        // Each refused mount's volume is gone from vold and the system server again.
        assertEquals(before, ObbImages.obbVolumes(mStorage));
        // The per-app start limit (four in ten seconds) is not left in the way of later tests.
        SystemClock.sleep(11000);
    }

    @Test
    public void anImageThatEndsOnItsOwnIsDroppedOnceAndMountsAgain() throws Exception {
        final String end = InstrumentationRegistry.getArguments().getString("obb_end", "");
        assumeTrue("needs obb_end=card or obb_end=kill", end.equals("card") || end.equals("kill"));
        final int before = ObbImages.obbVolumes(mStorage);
        final File first = obb("first.obb", true);
        final File second = obb("second.obb", true);

        File ended = null;
        ObbImages.Answers endedAnswers = null;
        if (end.equals("card")) {
            final VolumeInfo card = sdCard();
            assumeTrue("needs a mounted SD card", card != null);
            mStorage.unmount(card.getId());
            mCardToMount = card;
            final ObbImages.Answers a1 = ObbImages.mount(mStorage, first);
            final ObbImages.Answers a2 = ObbImages.mount(mStorage, second);
            // The card's drive VM gets its place first: the newest image's VM goes if both do not fit.
            mStorage.mount(card.getId());
            mCardToMount = null;
            final long until = SystemClock.uptimeMillis() + 20000;
            while (ended == null && SystemClock.uptimeMillis() < until) {
                if (a2.count(OnObbStateChangeListener.UNMOUNTED) > 0) {
                    ended = second;
                    endedAnswers = a2;
                } else if (a1.count(OnObbStateChangeListener.UNMOUNTED) > 0) {
                    ended = first;
                    endedAnswers = a1;
                } else {
                    SystemClock.sleep(200);
                }
            }
            if (ended == null) {
                // Without the fix the app is never told: show what the server still claims.
                Log.i(TAG, "no image ended: first mounted " + mStorage.isObbMounted(first.getPath())
                        + ", second mounted " + mStorage.isObbMounted(second.getPath()) + ", OBB volumes "
                        + ObbImages.obbVolumes(mStorage));
            }
            assertNotNull("the card's mount ended no image, or its app was not told", ended);
            // Room for the image again: the card goes once more for the remount below.
            mStorage.unmount(card.getId());
            mCardToMount = card;
        } else {
            endedAnswers = ObbImages.mount(mStorage, first);
            Log.i(TAG, "OBB-READY " + mStorage.getMountedObbPath(first.getPath()));
            assertEquals("the app was not told its image ended", OnObbStateChangeListener.UNMOUNTED,
                    endedAnswers.next(120));
            ended = first;
        }

        // Told once, not reported mounted, and gone from vold and the server.
        SystemClock.sleep(2000);
        assertEquals(1, endedAnswers.count(OnObbStateChangeListener.UNMOUNTED));
        assertFalse(mStorage.isObbMounted(ended.getPath()));
        assertNull(mStorage.getMountedObbPath(ended.getPath()));
        final int live = end.equals("card") ? 1 : 0;
        assertEquals(before + live, ObbImages.obbVolumes(mStorage));

        // It mounts again, and its file reads back.
        ObbImages.mount(mStorage, ended);
        final String path = mStorage.getMountedObbPath(ended.getPath());
        assertNotNull(path);
        assertEquals(ObbImages.TEXT, ObbImages.readFile(new File(path)));
        final ObbImages.Answers unmount = new ObbImages.Answers();
        mStorage.unmountObb(ended.getPath(), true, unmount);
        assertEquals(OnObbStateChangeListener.UNMOUNTED, unmount.next(30));
        assertEquals(before + live, ObbImages.obbVolumes(mStorage));
    }

    private VolumeInfo sdCard() {
        for (VolumeInfo vol : mStorage.getVolumes()) {
            if (vol.getType() == VolumeInfo.TYPE_PUBLIC && vol.getDisk() != null && vol.getDisk().isSd()
                    && vol.isMountedReadable()) {
                return vol;
            }
        }
        return null;
    }
}
