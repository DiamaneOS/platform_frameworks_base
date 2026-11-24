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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.TypedValue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.diamaneos.cleancopies.CleanCopyClient;
import com.android.internal.diamaneos.cleancopies.CleanCopyKey;
import com.android.internal.diamaneos.cleancopies.CleanImageFile;

import de.diamaneos.decode.client.tests.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Review 2b B1 and M8 through the system server as it is on the phone: these use only what the framework had before
 * the fixes too (keys through reflection), so the same APK shows each finding on a build without them and its fix on
 * a build with them. The copy check needs a work profile of this user (pm create-user --profileOf --managed): skipped
 * without one. The watcher check runs in the work profile only (am instrument --user).
 */
@RunWith(AndroidJUnit4.class)
public class CleanCopyProfilesDeviceTest {
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private static <T> T onThread(Callable<T> callable) throws Exception {
        final FutureTask<T> task = new FutureTask<>(callable);
        new Thread(task, "profiles-test").start();
        return task.get(30, TimeUnit.SECONDS);
    }

    private static String shell(String command) throws IOException {
        return DeviceShell.run(command);
    }

    /**
     * The key the framework on the phone keeps a copy under: with the user where keys carry one, else (a build before
     * the fix) without it. Reflection, so the same test runs on both.
     */
    private static String key(String apk, String file, int density, int[] box, int user) throws Exception {
        try {
            final Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
            return (String) CleanCopyClient.class.getMethod("key", Context.class, String.class, String.class,
                    int.class, int.class, int.class, int.class).invoke(null, app, apk, file, density,
                    box[0], box[1], user);
        } catch (NoSuchMethodException legacyKey) {
            // The before pack has numeric-user keys.
        }
        try {
            return (String) CleanCopyKey.class.getMethod("of", String.class, String.class, int.class, int.class,
                    int.class, int.class).invoke(null, apk, file, density, box[0], box[1], user);
        } catch (NoSuchMethodException e) {
            return (String) CleanCopyKey.class.getMethod("of", String.class, String.class, int.class, int.class,
                    int.class).invoke(null, apk, file, density, box[0], box[1]);
        }
    }

    // A work profile of this user, or -1.
    private int workProfile() {
        for (UserHandle profile : mContext.getSystemService(UserManager.class).getUserProfiles()) {
            if (profile.getIdentifier() != mContext.getUserId()) {
                return profile.getIdentifier();
            }
        }
        return -1;
    }

    private CleanImageFile waitForCopy(String key, long ms) {
        final long end = SystemClock.elapsedRealtime() + ms;
        CleanImageFile got = CleanCopyClient.read(mContext, key);
        while (got == null && SystemClock.elapsedRealtime() < end) {
            SystemClock.sleep(250);
            got = CleanCopyClient.read(mContext, key);
        }
        return got;
    }

    /**
     * B1: the same APK for this user and its work profile. A copy made in the work profile's VM run is never the
     * answer to this user's ask: this user's key finds nothing until its own copy is made, which is another file.
     */
    @Test
    public void systemServer_aProfilesCopyIsNeverTheUsersAnswer() throws Exception {
        final int work = workProfile();
        assumeTrue("needs a work profile of this user", work >= 0);
        final String apk = mContext.getApplicationInfo().sourceDir;
        final TypedValue value = new TypedValue();
        mContext.getResources().getValue(R.drawable.vm_test_orange, value, true);
        final String file = value.string.toString();
        final int density = Bitmap.DENSITY_NONE;
        final int[] box = CleanCopyClient.box(mContext);
        final String resource = mContext.getPackageName() + ":drawable/vm_test_orange";
        CleanCopyClient.forget(mContext, apk);
        final String workKey = key(apk, file, density, box, work);
        final String myKey = key(apk, file, density, box, mContext.getUserId());
        shell("su 0 content call --user 0 --uri content://" + CleanCopyClient.AUTHORITY
                + " --method request --extra apk:s:" + apk + " --extra file:s:" + file
                + " --extra density:i:" + density + " --extra width:i:" + box[0] + " --extra height:i:" + box[1]
                + " --extra user:i:" + work);
        final String workPath = "/data/system/diamaneos_clean_images_v4/" + CleanImageFile.fileName(workKey);
        final long end = SystemClock.uptimeMillis() + 40_000;
        boolean present = false;
        while (!present && SystemClock.uptimeMillis() < end) {
            present = shell("su 0 test -f " + workPath + " && echo present").contains("present");
            if (!present) SystemClock.sleep(250);
        }
        assertTrue("the work profile's positive-control copy was not made", present);
        assertNull("a parent without an item grant read the work copy", CleanCopyClient.read(mContext, workKey));
        assertNull("this user's ask found the work profile's copy", CleanCopyClient.read(mContext, myKey));
        CleanCopyClient.request(mContext, myKey, apk, file, density, box[0], box[1], resource,
                mContext.getUserId());
        assertNotNull("this user's own copy was not made", waitForCopy(myKey, 20_000));
        assertTrue("one file each", !CleanImageFile.fileName(myKey).equals(CleanImageFile.fileName(workKey)));
    }

    /**
     * M8: run in a work profile. A process of the profile hears that copies were made for its own user, never for its
     * parent: root asks for a copy for the parent (as the parent's launcher would), the profile's watch stays silent.
     */
    @Test
    public void watcherInAProfile_hearsNothingOfItsParent() throws Exception {
        final UserManager users = mContext.getSystemService(UserManager.class);
        final UserHandle parent = users.getProfileParent(UserHandle.of(mContext.getUserId()));
        assumeTrue("runs in a work profile (am instrument --user)",
                parent != null && parent.getIdentifier() != mContext.getUserId());
        assumeTrue("needs root to ask for the parent", shell("su 0 id").contains("uid=0"));
        final List<String> heard = new ArrayList<>();
        final android.os.IRemoteCallback.Stub watcher = new android.os.IRemoteCallback.Stub() {
            @Override
            public void sendResult(Bundle data) {
                if (data != null && data.getString(CleanCopyClient.EXTRA_PACKAGE) != null) {
                    synchronized (heard) {
                        heard.add(data.getString(CleanCopyClient.EXTRA_PACKAGE) + "/"
                                + data.getInt(CleanCopyClient.EXTRA_USER, -1));
                    }
                }
            }
        };
        assertTrue("not watching", onThread(() -> CleanCopyClient.watch(mContext, watcher)));
        final String apk = mContext.getApplicationInfo().sourceDir;
        final TypedValue value = new TypedValue();
        mContext.getResources().getValue(R.drawable.vm_test_green, value, true);
        final int[] box = CleanCopyClient.box(mContext);
        CleanCopyClient.forget(mContext, apk);
        shell("su 0 content call --user " + parent.getIdentifier() + " --uri content://"
                + CleanCopyClient.AUTHORITY + " --method " + CleanCopyClient.METHOD_REQUEST
                + " --extra apk:s:" + apk + " --extra file:s:" + value.string + " --extra density:i:0"
                + " --extra width:i:" + box[0] + " --extra height:i:" + box[1]
                + " --extra user:i:" + parent.getIdentifier());
        SystemClock.sleep(12_000);
        final String own = mContext.getPackageName() + "/" + mContext.getUserId();
        final String parents = mContext.getPackageName() + "/" + parent.getIdentifier();
        synchronized (heard) {
            assertTrue("a profile's process heard of its parent's copies: " + heard, !heard.contains(parents));
        }
        // And it does hear of its own user's.
        final String myKey = key(apk, value.string.toString(), 0, box, mContext.getUserId());
        CleanCopyClient.request(mContext, myKey, apk, value.string.toString(), 0, box[0], box[1], null,
                mContext.getUserId());
        final long end = SystemClock.elapsedRealtime() + 20_000;
        while (SystemClock.elapsedRealtime() < end) {
            synchronized (heard) {
                if (heard.contains(own)) {
                    break;
                }
            }
            SystemClock.sleep(250);
        }
        synchronized (heard) {
            assertTrue("its own user's copies were not told: " + heard, heard.contains(own));
        }
    }
}
