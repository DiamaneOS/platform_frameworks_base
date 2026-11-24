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

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageItemInfo;
import android.content.res.ImageFileGates;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.os.SystemClock;
import android.os.UserHandle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.diamaneos.cleancopies.CleanCopyGate;
import com.android.internal.diamaneos.cleancopies.CleanImageFile;

import de.diamaneos.decode.client.tests.R;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Review 2b M3: an icon the package manager loads without its copy fills in once the copy is made, whatever the
 * timing: the copy asked for the app's user, whose make it waits for, a copy made before it watched, one shown from an
 * older copy, and after an outage of the decode service. All but the last use only what the framework had before the
 * fix too, so the same APK shows the finding on a build without it.
 */
@RunWith(AndroidJUnit4.class)
public class CleanCopyWaitersDeviceTest {
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private int mCreatedWork = -1;

    @After
    public void noProcessGate() throws Exception {
        ImageFileGates.setProcessDefault(null);
        CleanCopyGate.setModeForTest(-1);
        if (mCreatedWork > 0) {
            final String removed = shell("pm remove-user " + mCreatedWork);
            assertTrue(removed, removed.contains("Success"));
        }
    }

    private static String shell(String command) throws Exception {
        try (java.io.InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command))) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private int workUser() throws Exception {
        final String supplied = InstrumentationRegistry.getArguments().getString("workUser");
        if (supplied != null) return Integer.parseInt(supplied);
        final String made = shell("pm create-user --profileOf 0 --managed sol2arg-waiters");
        final java.util.regex.Matcher id = java.util.regex.Pattern.compile("created user id (\\d+)").matcher(made);
        assertTrue(made, id.find());
        mCreatedWork = Integer.parseInt(id.group(1));
        assertTrue(shell("pm install-existing --user " + mCreatedWork + " " + mContext.getPackageName())
                .contains("installed"));
        final String started = shell("am start-user -w " + mCreatedWork);
        assertTrue(started, started.contains("Success"));
        return mCreatedWork;
    }

    private static <T> T onThread(Callable<T> callable) throws Exception {
        final FutureTask<T> task = new FutureTask<>(callable);
        new Thread(task, "waiters-test").start();
        return task.get(30, TimeUnit.SECONDS);
    }

    private static CleanImageFile solid(int argb) {
        final Bitmap bitmap = Bitmap.createBitmap(9, 5, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(argb);
        final ByteBuffer pixels = ByteBuffer.allocate(4 * 9 * 5);
        bitmap.copyPixelsToBuffer(pixels);
        return new CleanImageFile("k", "", 9, 5, 0, CleanImageFile.COLOR_SRGB, null, null, pixels.array());
    }

    /** A source of copies made here, recording the users asked for and how often it was read. */
    private static final class Source implements CleanCopyGate.Source {
        final List<Integer> users = new ArrayList<>();
        volatile CleanImageFile copy;
        volatile CleanImageFile older;
        volatile int nullReads; // reads that find nothing before the copy shows
        private int reads;

        @Override
        public synchronized CleanImageFile read(String key) {
            final CleanImageFile c = copy;
            android.util.Log.i("WaiterProvenance", "read=" + (++reads) + " key=" + Integer.toHexString(key.hashCode())
                    + " published=" + (c != null) + " thread=" + Thread.currentThread().getName());
            if (c == null || nullReads-- > 0) {
                return null;
            }
            return new CleanImageFile(key, "", c.width, c.height, c.density, c.colorSpace, null, null, c.pixels);
        }

        @Override
        public CleanImageFile fallback(String apkPath, String file, int density, int maxWidth, int maxHeight,
                String resourceName, int userId) {
            final CleanImageFile o = older;
            return o == null ? null : new CleanImageFile("old", "", o.width, o.height, o.density, o.colorSpace,
                    null, null, o.pixels);
        }

        @Override
        public synchronized void request(String key, String apkPath, String file, int density, int maxWidth,
                int maxHeight, String resourceName, int userId) {
            users.add(userId);
            android.util.Log.i("WaiterProvenance", "request=" + users.size() + " user=" + userId
                    + " key=" + Integer.toHexString(key.hashCode()) + " published=" + (copy != null)
                    + " thread=" + Thread.currentThread().getName());
        }
    }

    private Drawable packageManagerIcon(int resId, ApplicationInfo app) throws Exception {
        final PackageItemInfo item = new PackageItemInfo();
        item.packageName = mContext.getPackageName();
        item.icon = resId;
        return onThread(() -> mContext.getPackageManager().loadUnbadgedItemIcon(item, app));
    }

    private static Drawable waitForBitmap(Drawable icon, long ms) {
        final long end = SystemClock.elapsedRealtime() + ms;
        while (SystemClock.elapsedRealtime() < end
                && !(((DrawableWrapper) icon).getDrawable() instanceof BitmapDrawable)) {
            SystemClock.sleep(50);
        }
        return ((DrawableWrapper) icon).getDrawable();
    }

    private static void assertRequestedUser(Source source, int user) {
        synchronized (source) {
            assertTrue("no copy requested", !source.users.isEmpty());
            for (int requested : source.users) assertEquals("the user asked for", user, requested);
        }
    }

    /**
     * the package manager loads an icon of an app of another user (a work profile's, as SystemUI and Settings do):
     * the copy is asked for that user, whose make the filling icon waits for, not for this process's user.
     */
    @Test
    public void packageManagerIcon_ofAnotherUsersApp_asksForThatUsersCopy() throws Exception {
        final Source source = new Source();
        ImageFileGates.setProcessDefault(CleanCopyGate.forTest(mContext, source, false));
        CleanCopyGate.setModeForTest(CleanCopyGate.MODE_ALL);
        final int work = workUser();
        final ApplicationInfo app = new ApplicationInfo(mContext.getApplicationInfo());
        app.uid = UserHandle.getUid(work, UserHandle.getAppId(app.uid));
        final Drawable icon = packageManagerIcon(R.drawable.vm_test_red, app);
        assertTrue(String.valueOf(icon), icon instanceof DrawableWrapper);
        assertRequestedUser(source, work);
        android.util.Log.i("WaiterProvenance", "publish after user assertion user=" + work);
        source.copy = solid(0xff102030);
        CleanCopyGate.madeForTest(mContext.getPackageName(), work);
        final Drawable inner = waitForBitmap(icon, 5_000);
        assertTrue(String.valueOf(inner), inner instanceof BitmapDrawable);
        assertEquals(0xff102030, ((BitmapDrawable) inner).getBitmap().getPixel(4, 2));
        assertRequestedUser(source, work);
    }

    /** a copy made between the miss and the filling icon's watch is not missed: the icon looks once more. */
    @Test
    public void packageManagerIcon_madeBeforeItsWatch_fillsIn() throws Exception {
        final Source source = new Source();
        source.copy = solid(0xff405060);
        source.nullReads = 1; // the load misses, the copy is there right after
        ImageFileGates.setProcessDefault(CleanCopyGate.forTest(mContext, source, false));
        CleanCopyGate.setModeForTest(CleanCopyGate.MODE_ALL);
        final Drawable icon = packageManagerIcon(R.mipmap.vm_test_icon, mContext.getApplicationInfo());
        assertTrue(String.valueOf(icon), icon instanceof DrawableWrapper);
        // No "made" is ever sent: the system server made it before the icon watched.
        final Drawable inner = waitForBitmap(icon, 5_000);
        assertTrue("never filled in: " + inner, inner instanceof BitmapDrawable);
        assertEquals(0xff405060, ((BitmapDrawable) inner).getBitmap().getPixel(4, 2));
    }

    /** an icon shown from an older copy of the same resource is replaced by the exact copy once it is made. */
    @Test
    public void packageManagerIcon_fromAnOlderCopy_becomesTheExactCopy() throws Exception {
        final Source source = new Source();
        source.older = solid(0xff445566);
        ImageFileGates.setProcessDefault(CleanCopyGate.forTest(mContext, source, false));
        CleanCopyGate.setModeForTest(CleanCopyGate.MODE_ALL);
        final Drawable icon = packageManagerIcon(R.drawable.vm_test_patch, mContext.getApplicationInfo());
        assertTrue("shown from the older copy for good: " + icon, icon instanceof DrawableWrapper);
        assertEquals(0xff445566,
                ((BitmapDrawable) ((DrawableWrapper) icon).getDrawable()).getBitmap().getPixel(4, 2));
        source.older = null;
        source.copy = solid(0xff778899);
        CleanCopyGate.madeForTest(mContext.getPackageName(), mContext.getUserId());
        final long end = SystemClock.elapsedRealtime() + 5_000;
        int pixel = 0;
        while (SystemClock.elapsedRealtime() < end) {
            final Drawable inner = ((DrawableWrapper) icon).getDrawable();
            pixel = inner instanceof BitmapDrawable ? ((BitmapDrawable) inner).getBitmap().getPixel(4, 2) : 0;
            if (pixel == 0xff778899) {
                break;
            }
            SystemClock.sleep(50);
        }
        assertEquals("the exact copy", 0xff778899, pixel);
    }

    /**
     * M3 (needs the fix's test entry): after an outage the system server makes nothing by itself: an icon that waits loads again when the decode
     * service answers, and fills in from the copy that load finds.
     */
    @Test
    public void waitingIcon_loadsAgain_whenTheDecodeServiceAnswers() throws Exception {
        final Source source = new Source();
        ImageFileGates.setProcessDefault(CleanCopyGate.forTest(mContext, source, false));
        CleanCopyGate.setModeForTest(CleanCopyGate.MODE_ALL);
        final Drawable icon = packageManagerIcon(R.drawable.vm_test_orange, mContext.getApplicationInfo());
        assertTrue(String.valueOf(icon), icon instanceof DrawableWrapper);
        SystemClock.sleep(500);
        assertTrue("no copy yet", !(((DrawableWrapper) icon).getDrawable() instanceof BitmapDrawable));
        source.copy = solid(0xff345678);
        CleanCopyGate.decodeAnswersForTest();
        final long end = SystemClock.elapsedRealtime() + 5_000;
        while (SystemClock.elapsedRealtime() < end
                && !(((DrawableWrapper) icon).getDrawable() instanceof BitmapDrawable)) {
            SystemClock.sleep(50);
        }
        final Drawable inner = ((DrawableWrapper) icon).getDrawable();
        assertTrue(String.valueOf(inner), inner instanceof BitmapDrawable);
        assertEquals(0xff345678, ((BitmapDrawable) inner).getBitmap().getPixel(4, 2));
    }
    @Test
    public void liveSubscriptions_surviveMoreThan512MountedTargets() throws Exception {
        ImageFileGates.setProcessDefault(CleanCopyGate.forTest(mContext, new Source(), false));
        CleanCopyGate.setModeForTest(CleanCopyGate.MODE_ALL);
        final java.util.concurrent.CountDownLatch first = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean checking = new java.util.concurrent.atomic.AtomicBoolean();
        final List<CleanCopyGate.MadeListener> held = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            final int index = i;
            final CleanCopyGate.MadeListener target = (pkg, user) -> {
                if (index == 0 && checking.get()) {
                    first.countDown();
                }
            };
            held.add(target);
            CleanCopyGate.whenMade(target, "mounted." + i, mContext.getUserId());
        }
        checking.set(true);
        CleanCopyGate.madeForTest("mounted.0", mContext.getUserId());
        assertTrue("the first live subscription was evicted", first.await(5, TimeUnit.SECONDS));
        assertEquals(600, held.size());
    }

}
