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
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.TypedValue;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.android.internal.diamaneos.cleancopies.CleanCopyClient;
import com.android.internal.diamaneos.cleancopies.CleanCopyKey;
import com.android.internal.diamaneos.cleancopies.CleanImageFile;
import de.diamaneos.decode.client.tests.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public class CopyProviderAudienceDeviceTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final int work = Integer.parseInt(InstrumentationRegistry.getArguments().getString("workUser", "10"));

    private static String shell(String cmd) throws Exception {
        return DeviceShell.run(cmd);
    }

    private String key(String apk, String member, int[] box, int user) throws Exception {
        try {
            return (String) CleanCopyClient.class.getMethod("key", Context.class, String.class, String.class,
                    int.class, int.class, int.class, int.class).invoke(null, context, apk, member, 0,
                    box[0], box[1], user);
        } catch (NoSuchMethodException old) {
            return CleanCopyKey.of(apk, member, 0, box[0], box[1], user);
        }
    }

    private void rootRequest(String apk, String member, int[] box, int user) throws Exception {
        shell("su 0 content call --user 0 --uri content://" + CleanCopyClient.AUTHORITY
                + " --method request --extra apk:s:" + apk + " --extra file:s:" + member
                + " --extra density:i:0 --extra width:i:" + box[0] + " --extra height:i:" + box[1]
                + " --extra user:i:" + user);
    }

    private void waitForFile(String key) throws Exception {
        final String path = "/data/system/diamaneos_clean_images_v4/" + CleanImageFile.fileName(key);
        final long end = SystemClock.uptimeMillis() + 25000;
        while (SystemClock.uptimeMillis() < end) {
            if (shell("su 0 test -f " + path + " && echo present").contains("present")) {
                return;
            }
            SystemClock.sleep(250);
        }
        fail("positive-control copy was never made");
    }

    @Test
    public void aSecondCallerCannotReadOrWatchAnUnrequestedWorkCopy() throws Exception {
        final String apk = context.getApplicationInfo().sourceDir;
        final TypedValue value = new TypedValue();
        context.getResources().getValue(R.drawable.vm_test_orange, value, true);
        final int[] box = CleanCopyClient.box(context);
        final String key = key(apk, value.string.toString(), box, work);
        CleanCopyClient.forget(context, apk);
        final AtomicInteger heard = new AtomicInteger();
        final android.os.IRemoteCallback callback = new android.os.IRemoteCallback.Stub() {
            @Override public void sendResult(Bundle data) {
                if (data != null && context.getPackageName().equals(data.getString(CleanCopyClient.EXTRA_PACKAGE))
                        && data.getInt(CleanCopyClient.EXTRA_USER, -1) == work) {
                    heard.incrementAndGet();
                }
            }
        };
        assertTrue(CleanCopyClient.watch(context, callback));
        rootRequest(apk, value.string.toString(), box, work);
        waitForFile(key);
        assertNull("parent caller read an unrequested work file by name", CleanCopyClient.read(context, key));
        SystemClock.sleep(3000);
        assertEquals("watcher heard a package it never subscribed to", 0, heard.get());
    }

    @Test
    public void workOnlyApkCannotBeMadeAsTheSecondCallersOwnUser() throws Exception {
        final String apk = InstrumentationRegistry.getArguments().getString("fixtureApk");
        final String member = InstrumentationRegistry.getArguments().getString("fixtureMember", "res/drawable/probe.png");
        assertNotNull("fixtureApk is required", apk);
        assertFalse(shell("pm list packages --user " + context.getUserId()
                + " de.diamaneos.copy.fixture").contains("package:de.diamaneos.copy.fixture"));
        final int[] box = CleanCopyClient.box(context);
        final String own = key(apk, member, box, context.getUserId());
        CleanCopyClient.forget(context, apk);
        CleanCopyClient.request(context, own, apk, member, 0, box[0], box[1], null, context.getUserId());
        SystemClock.sleep(10000);
        assertNull("work-only APK was relabeled as personal", CleanCopyClient.read(context, own));
        final String actual = key(apk, member, box, work);
        rootRequest(apk, member, box, work);
        waitForFile(actual);
    }

    @Test
    public void homeRoleCannotAttestAnArbitraryFdAsACrossProfileShortcut() throws Exception {
        assumeTrue("temporary test home role required", InstrumentationRegistry.getArguments()
                .getString("brokerAsHome", "0").equals("1"));
        assertTrue(context.getSystemService(android.app.role.RoleManager.class)
                .isRoleHeld(android.app.role.RoleManager.ROLE_HOME));
        final Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xff123456);
        final File input = new File(context.getCacheDir(), "unattested-shortcut.png");
        try {
            try (OutputStream out = new FileOutputStream(input)) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
            }
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)) {
                final Bundle request = new Bundle();
                request.putParcelable(CleanCopyClient.EXTRA_FD, fd);
                request.putString(CleanCopyClient.EXTRA_PACKAGE, context.getPackageName());
                request.putInt(CleanCopyClient.EXTRA_USER, work);
                final Bundle reply = context.getContentResolver().call(CleanCopyClient.URI,
                        CleanCopyClient.METHOD_SHORTCUT, null, request);
                final Bitmap got = reply == null ? null : reply.getParcelable(CleanCopyClient.EXTRA_BITMAP,
                        Bitmap.class);
                assertNull("a role is not evidence for this FD and shortcut", got);
            }
        } finally {
            input.delete();
            bitmap.recycle();
        }
    }
    @Test
    public void publishCheckedUriShortcutInItsOwnUser() {
        final android.content.Intent intent = new android.content.Intent(context, AudienceHomeActivity.class)
                .setAction(android.content.Intent.ACTION_MAIN);
        final android.content.pm.ShortcutInfo shortcut = new android.content.pm.ShortcutInfo.Builder(context,
                "copy-checked-uri").setShortLabel("Copy test").setIntent(intent)
                .setIcon(android.graphics.drawable.Icon.createWithContentUri(UserImageProvider.IMAGE)).build();
        assertTrue(context.getSystemService(android.content.pm.ShortcutManager.class)
                .setDynamicShortcuts(java.util.List.of(shortcut)));
    }

    @Test
    public void actualLauncherAppsWorkShortcutStillReturnsItsAuthorizedPixels() throws Exception {
        assumeTrue("temporary test home role required", InstrumentationRegistry.getArguments()
                .getString("brokerAsHome", "0").equals("1"));
        final android.content.pm.LauncherApps apps = context.getSystemService(android.content.pm.LauncherApps.class);
        final android.content.pm.LauncherApps.ShortcutQuery query = new android.content.pm.LauncherApps.ShortcutQuery()
                .setPackage(context.getPackageName()).setShortcutIds(java.util.List.of("copy-checked-uri"))
                .setQueryFlags(android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC);
        final java.util.List<android.content.pm.ShortcutInfo> shortcuts = apps.getShortcuts(query,
                android.os.UserHandle.of(work));
        assertNotNull(shortcuts);
        assertEquals("work fixture shortcut absent", 1, shortcuts.size());
        final android.content.pm.ShortcutInfo shortcut = shortcuts.get(0);
        final long end = SystemClock.uptimeMillis() + 15000;
        Bitmap made = null;
        while (made == null && SystemClock.uptimeMillis() < end) {
            try {
                made = (Bitmap) CleanCopyClient.class.getMethod("shortcutIcon", Context.class,
                        android.content.pm.ShortcutInfo.class).invoke(null, context, shortcut);
            } catch (NoSuchMethodException old) {
                final android.net.Uri uri = android.content.ContentProvider.maybeAddUserId(UserImageProvider.IMAGE,
                        work);
                made = CleanCopyClient.shortcutIcon(context,
                        context.getContentResolver().openFileDescriptor(uri, "r"), context.getPackageName(), work);
            }
            if (made == null) SystemClock.sleep(250);
        }
        assertNotNull("authorized work shortcut did not decode", made);
        assertEquals(UserImageProvider.WORK, made.getPixel(made.getWidth() / 2, made.getHeight() / 2));
    }


    @Test
    public void publishShortcutOpenFixtures() {
        final android.content.Intent intent = new android.content.Intent(context, AudienceHomeActivity.class)
                .setAction(android.content.Intent.ACTION_MAIN);
        final Bitmap file = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        file.eraseColor(0xff00af00);
        final var lag = android.graphics.drawable.Icon.createWithContentUri(
                UserImageProvider.image(0xff0000af, 7000));
        final var manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        assertTrue(manager.setDynamicShortcuts(java.util.List.of(
                new android.content.pm.ShortcutInfo.Builder(context, "lag-a")
                        .setShortLabel("Delayed A").setIntent(intent).setIcon(lag).build(),
                new android.content.pm.ShortcutInfo.Builder(context, "lag-b")
                        .setShortLabel("Delayed B").setIntent(intent).setIcon(lag).build(),
                new android.content.pm.ShortcutInfo.Builder(context, "ready-file")
                        .setShortLabel("Ready file").setIntent(intent)
                        .setIcon(android.graphics.drawable.Icon.createWithBitmap(file)).build())));
    }

    @Test
    public void stalledWorkShortcut_leavesFileShortcutAvailable() throws Exception {
        assertTrue(context.getSystemService(android.app.role.RoleManager.class)
                .isRoleHeld(android.app.role.RoleManager.ROLE_HOME));
        final var apps = context.getSystemService(android.content.pm.LauncherApps.class);
        final var query = new android.content.pm.LauncherApps.ShortcutQuery()
                .setPackage(context.getPackageName())
                .setShortcutIds(java.util.List.of("lag-a", "lag-b", "ready-file"))
                .setQueryFlags(android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC);
        final var shortcuts = new java.util.HashMap<String, android.content.pm.ShortcutInfo>();
        for (var shortcut : apps.getShortcuts(query, android.os.UserHandle.of(work))) {
            shortcuts.put(shortcut.getId(), shortcut);
        }
        assertEquals(3, shortcuts.size());
        final var callers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            // Warm the VM with the healthy source so cold-start cost does not obscure the opener check.
            assertNotNull(CleanCopyClient.shortcutIcon(context, shortcuts.get("ready-file")));
            final var first = callers.submit(() -> CleanCopyClient.shortcutIcon(context, shortcuts.get("lag-a")));
            SystemClock.sleep(250);
            assertFalse("the delayed provider did not enter its open", first.isDone());
            final var second = callers.submit(() -> CleanCopyClient.shortcutIcon(context, shortcuts.get("lag-b")));
            SystemClock.sleep(1600);
            final Bitmap made = CleanCopyClient.shortcutIcon(context, shortcuts.get("ready-file"));
            assertNotNull("a file-backed icon waited behind the delayed provider", made);
            assertEquals(0xff00af00, made.getPixel(made.getWidth() / 2, made.getHeight() / 2));
            assertNull(first.get(3, java.util.concurrent.TimeUnit.SECONDS));
            assertNull(second.get(3, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            callers.shutdownNow();
        }
    }
}
