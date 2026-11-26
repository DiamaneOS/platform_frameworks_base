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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.os.SystemClock;
import android.os.storage.OnObbStateChangeListener;
import android.os.storage.StorageManager;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * An OBB belongs to the app that mounted it in its own Android user. The same app in another user (the same shared
 * app gid) neither learns whether or where it is mounted nor unmounts it.
 *
 * <p>Two runs at once, chosen by the instrumentation argument obb_role. "owner" (in the first user) mounts its image,
 * logs OBB-OWNER-MOUNTED and keeps it until the host puts the file obb-owner-done in its files folder (at most three
 * minutes), then checks it was never unmounted. "other" (the same package in a second user, owner_path naming the
 * owner's image) checks it sees nothing and cannot unmount it. Skipped without obb_role.
 */
@RunWith(AndroidJUnit4.class)
public class ObbOwnerDeviceTest {
    private static final String TAG = "ObbOwnerDeviceTest";
    private static final String DONE = "obb-owner-done";

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final StorageManager mStorage = mContext.getSystemService(StorageManager.class);

    @Test
    public void anImageIsSeenAndUnmountedByItsOwnerOnly() throws Exception {
        final String role = InstrumentationRegistry.getArguments().getString("obb_role", "");
        assumeTrue("needs obb_role=owner or obb_role=other", role.equals("owner") || role.equals("other"));
        if (role.equals("owner")) {
            owner();
        } else {
            other(InstrumentationRegistry.getArguments().getString("owner_path"));
        }
    }

    private void owner() throws Exception {
        final File done = new File(mContext.getFilesDir(), DONE);
        done.delete();
        final File image = ObbImages.write(mContext, new File(mContext.getObbDir(), "owner.obb"), true);
        try {
            final ObbImages.Answers answers = ObbImages.mount(mStorage, image);
            Log.i(TAG, "OBB-OWNER-MOUNTED " + image.getPath());
            final long until = SystemClock.uptimeMillis() + 180000;
            while (!done.exists() && SystemClock.uptimeMillis() < until) {
                SystemClock.sleep(500);
            }
            assertTrue("the other run did not finish", done.exists());
            assertEquals("the image was unmounted for its owner", 0,
                    answers.count(OnObbStateChangeListener.UNMOUNTED));
            assertTrue(mStorage.isObbMounted(image.getPath()));
            final ObbImages.Answers unmount = new ObbImages.Answers();
            mStorage.unmountObb(image.getPath(), true, unmount);
            assertEquals(OnObbStateChangeListener.UNMOUNTED, unmount.next(30));
        } finally {
            image.delete();
            done.delete();
        }
    }

    private void other(String ownerPath) throws Exception {
        assertTrue("needs owner_path", ownerPath != null && !ownerPath.isEmpty());
        assertFalse("another user's image is reported mounted", mStorage.isObbMounted(ownerPath));
        assertNull("another user's image path is given out", mStorage.getMountedObbPath(ownerPath));
        final ObbImages.Answers answers = new ObbImages.Answers();
        mStorage.unmountObb(ownerPath, true, answers);
        final int state = answers.next(10);
        assertTrue("another user's image was unmounted (" + state + ")",
                state != OnObbStateChangeListener.UNMOUNTED);
    }
}
