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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ImageFileGates;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.diamaneos.cleancopies.CleanCopyGate;
import com.android.internal.diamaneos.cleancopies.CleanCopyKey;
import com.android.internal.diamaneos.cleancopies.CleanImageFile;

import de.diamaneos.decode.client.tests.R;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Clean copies are of one Android user (review 2b, B1): the store's stand-ins, its removals and its index go by user,
 * the in-memory keys of SystemUI's caches carry the user, and a gate's load of another user's app asks for that
 * user's copy.
 */
@RunWith(AndroidJUnit4.class)
public class CleanCopyUsersDeviceTest {
    private static final int WORK = 10;
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final File mTempStore = new File(mContext.getCacheDir(), "users-store");

    @After
    public void noProcessGate() {
        ImageFileGates.setProcessDefault(null);
        CleanCopyGate.setModeForTest(-1);
        new CleanImageStore(mTempStore).clear();
    }

    private static <T> T onThread(Callable<T> callable) throws Exception {
        final FutureTask<T> task = new FutureTask<>(callable);
        new Thread(task, "users-test").start();
        return task.get(30, TimeUnit.SECONDS);
    }

    private static Bitmap solid(int w, int h, int argb) {
        final Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(argb);
        return bitmap;
    }

    /** The newest copy of a resource stands in for the same user only, and a removed user takes only its copies. */
    @Test
    public void store_standInsAndRemovals_byUser() throws Exception {
        final CleanImageStore store = new CleanImageStore(mTempStore);
        final String apk = "/data/app/p/base.apk";
        final String mine = CleanCopyKey.of(apk, "res/a.png", 480, 100, 100, 0);
        final String work = CleanCopyKey.of(apk, "res/a.png", 480, 100, 100, WORK);
        final String mineId = CleanCopyKey.identity("p.app", 0, "p.app:mipmap/ic", "res/a.png", 100, 100);
        final String workId = CleanCopyKey.identity("p.app", WORK, "p.app:mipmap/ic", "res/a.png", 100, 100);
        final Object[] out = onThread(() -> {
            final Bitmap b = solid(4, 4, 0xff111111);
            b.setDensity(480);
            store.saveCopy(work, workId, b, null, System.currentTimeMillis());
            final String beforeMine = store.fallbackKey(mineId, 480);
            store.saveCopy(mine, mineId, b, null, System.currentTimeMillis());
            final String afterMine = store.fallbackKey(mineId, 480);
            final int userOfWork = store.userOfFile(CleanImageFile.fileName(work));
            store.forgetUser(WORK);
            return new Object[] {beforeMine, afterMine, userOfWork, store.has(work), store.has(mine)};
        });
        assertNull("a work copy never stands in for the user's own", out[0]);
        assertEquals(mine, out[1]);
        assertEquals(WORK, out[2]);
        assertEquals("the removed user's copy went", false, out[3]);
        assertEquals("the other user's stayed", true, out[4]);
    }

    /** SystemUI's in-memory keys: an app resource file and a data icon are never another user's entry. */
    @Test
    public void memoryKeys_carryTheUser() {
        final String apk = mContext.getApplicationInfo().sourceDir;
        assertNotEquals(AppResourceImages.keyOf(apk, "res/a.png", 480, 100, 100, 0),
                AppResourceImages.keyOf(apk, "res/a.png", 480, 100, 100, WORK));
        final Icon data = Icon.createWithData(new byte[] {1, 2, 3, 4}, 0, 4);
        final String mine = VmIcons.cacheKey(data, userContext(0), "p", "10x10");
        final String work = VmIcons.cacheKey(data, userContext(WORK), "p", "10x10");
        assertNotEquals("the same bytes posted by the same app in two profiles", mine, work);
        assertTrue(work, work.startsWith("data:" + WORK + ":"));
    }

    private Context userContext(int user) {
        return new ContextWrapper(mContext) {
            @Override
            public int getUserId() {
                return user;
            }
        };
    }

    /** A source that records the users the gate asks copies for. */
    private static final class UserSource implements CleanCopyGate.Source {
        final List<Integer> users = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        volatile CleanImageFile copy;

        @Override
        public CleanImageFile read(String key) {
            final CleanImageFile c = copy;
            return c == null ? null : new CleanImageFile(key, "", c.width, c.height, c.density, c.colorSpace,
                    null, null, c.pixels);
        }

        @Override
        public CleanImageFile fallback(String apkPath, String file, int density, int maxWidth, int maxHeight,
                String resourceName, int userId) {
            return null;
        }

        @Override
        public synchronized void request(String key, String apkPath, String file, int density, int maxWidth,
                int maxHeight, String resourceName, int userId) {
            users.add(userId);
            keys.add(key);
        }
    }

    /** A load of a work profile's app asks for, and keys, the work profile's copy; any other load this user's. */
    @Test
    public void gate_loadOfAUsersApp_asksForThatUsersCopy() throws Exception {
        final UserSource source = new UserSource();
        final Resources res = mContext.createConfigurationContext(mContext.getResources().getConfiguration())
                .getResources();
        res.setImageFileGate(CleanCopyGate.forTest(mContext, source, false));
        onThread(() -> CleanCopyGate.iconLoad(WORK, () -> res.getDrawable(R.drawable.vm_test_orange, null)));
        onThread(() -> res.getDrawable(R.drawable.vm_test_blue, null));
        assertEquals(List.of(WORK, mContext.getUserId()), source.users);
        assertTrue(source.keys.get(0), source.keys.get(0).endsWith(":u" + WORK));
        assertTrue(source.keys.get(1), source.keys.get(1).endsWith(":u" + mContext.getUserId()));
    }
    private Context serialContext(int sourceSerial, int resolverSerial) {
        final android.os.UserManager users = new android.os.UserManager(mContext, new android.os.IUserManager.Default()) {
            @Override public int getUserSerialNumber(int user) {
                return user == 10 ? sourceSerial : user == 11 ? resolverSerial : -1;
            }
        };
        return new android.content.ContextWrapper(mContext) {
            @Override public int getUserId() { return 11; }
            @Override public Object getSystemService(String name) {
                return Context.USER_SERVICE.equals(name) ? users : super.getSystemService(name);
            }
        };
    }

    @Test
    public void qualifiedUriKeys_bindBothTheSourceAndResolverIncarnations() {
        final android.graphics.drawable.Icon icon = android.graphics.drawable.Icon.createWithContentUri(
                "content://10@de.diamaneos.decode.client.tests.user/image");
        final String old = VmIcons.cacheKey(icon, serialContext(41, 51), "p", "10x10");
        assertNotEquals(old, VmIcons.cacheKey(icon, serialContext(41, 52), "p", "10x10"));
        assertNotEquals(old, VmIcons.cacheKey(icon, serialContext(42, 51), "p", "10x10"));
    }

}
