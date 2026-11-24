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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.DecodeGate;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whose image a decode gate request is, end to end: the owner a scope names travels with the request through the
 * system server's provider to the decode service (its per-request DecoderUsers line, debuggable builds), answers kept
 * by the gate are never given to another owner, a user the caller may not act for is refused, and the service keeps
 * two users' per-user content in separate VM starts in both orders. A work profile (or with -e profileType private a
 * Private Space) is made for the test and removed after it.
 */
@RunWith(AndroidJUnit4.class)
public class OwnerDeviceTest {
    private static final String TAG = "OwnerDeviceTest";
    private static final Pattern REQUEST = Pattern.compile(
            "request of user (\\d+) (shared|per-user) in VM start (\\d+), .*, lane (\\S+)");
    private static int sProfile = -1;
    // Another start in every run: the service keeps answers per owner across runs.
    private static int sSeed = (int) (System.nanoTime() & 0xffff);

    @BeforeClass
    public static void workProfile() throws Exception {
        // -e profileType private: a Private Space instead of a work profile.
        final boolean privateSpace = "private".equals(
                InstrumentationRegistry.getArguments().getString("profileType"));
        final String made = shell("pm create-user --profileOf 0 "
                + (privateSpace ? "--user-type android.os.usertype.profile.PRIVATE" : "--managed") + " owner-test");
        final Matcher m = Pattern.compile("created user id (\\d+)").matcher(made);
        assertTrue("no work profile made: " + made, m.find());
        sProfile = Integer.parseInt(m.group(1));
        Log.i(TAG, "work profile " + sProfile + ": " + shell("am start-user -w " + sProfile).trim());
    }

    @AfterClass
    public static void removeProfile() throws Exception {
        if (sProfile > 0) {
            Log.i(TAG, "work profile " + sProfile + " removed: " + shell("pm remove-user " + sProfile).trim());
        }
    }

    @Before
    public void gated() {
        DecodeGate.setForcedForTest(true);
        DecodeGate.setWaitForTest(5_000L);
        DecodeGate.resetForTest();
    }

    @After
    public void asBefore() {
        DecodeGate.setForcedForTest(null);
        DecodeGate.setWaitForTest(null);
        DecodeGate.resetForTest();
    }

    // A PNG of its own each time (another colour), so neither the gate nor the service has it already.
    private static byte[] png() {
        final Bitmap bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.rgb(17 + sSeed % 200, (sSeed * 7) % 256, (sSeed++ * 13) % 256));
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        return out.toByteArray();
    }

    private static Bitmap decodeAs(int user, byte[] png) {
        try (DecodeGate.OwnerScope owner = DecodeGate.ownerScope(user)) {
            return BitmapFactory.decodeByteArray(png, 0, png.length);
        }
    }

    @Test
    public void answersAreKeptPerOwner() {
        final byte[] png = png();
        assertNotNull("decoded for user 0", decodeAs(0, png));
        final int hits = DecodeGate.countsForTest()[4];
        assertNotNull(decodeAs(0, png));
        final int again = DecodeGate.countsForTest()[4];
        assertTrue("the same owner asks again: the answers kept (probe and decode)", again > hits);
        assertNull("a bare FD cannot prove a foreign source user", decodeAs(sProfile, png));
        assertEquals("another owner: never the answers kept for user 0", again, DecodeGate.countsForTest()[4]);
    }

    @Test
    public void aUserTheCallerMayNotActForIsRefused() {
        assertNull("no such user: refused, never decoded as this app's", decodeAs(sProfile + 500, png()));
    }

    @Test
    public void unverifiedForeignSourcesNeverReachTheService() throws Exception {
        final double since = System.currentTimeMillis() / 1000.0;
        final int[] users = {0, sProfile, sProfile, 0};
        for (int user : users) {
            Bitmap bitmap = decodeAs(user, png());
            if (user == 0) assertNotNull("same-user positive control", bitmap);
            else assertNull("a foreign source needs trusted acquisition", bitmap);
        }
        final String log = shell(String.format(java.util.Locale.ROOT,
                "logcat -d -v epoch -T %.3f -s DecoderUsers:I", since));
        final Matcher m = REQUEST.matcher(log);
        boolean seen = false;
        while (m.find()) {
            if (m.group(4).equals("?gate:uid" + Process.myUid())) {
                assertEquals("no unverified foreign source reached the guest", 0, Integer.parseInt(m.group(1)));
                seen = true;
            }
        }
        assertTrue("the same-user control reached the guest: " + log, seen);
    }

    @Test
    public void aRegionKeepsTheUserOfItsInputAfterTheScopeEnds() throws Exception {
        byte[] data = png();
        android.graphics.BitmapRegionDecoder region;
        try (DecodeGate.OwnerScope scope = DecodeGate.ownerScope(0)) {
            region = android.graphics.BitmapRegionDecoder.newInstance(data, 0, data.length);
        }
        final double since = System.currentTimeMillis() / 1000.0;
        try (DecodeGate.OwnerScope scope = DecodeGate.ownerScope(sProfile)) {
            assertNotNull("the retained source is still of user 0", region.decodeRegion(
                    new android.graphics.Rect(0, 0, 12, 8), null));
        } finally {
            region.recycle();
        }
        final String log = shell(String.format(java.util.Locale.ROOT,
                "logcat -d -v epoch -T %.3f -s DecoderUsers:I", since));
        final Matcher request = REQUEST.matcher(log);
        boolean seen = false;
        while (request.find()) {
            String lane = request.group(4);
            if (lane.equals("?gate:uid" + Process.myUid()) || lane.contains("@" + Process.myUid() + "/bytes")) {
                assertEquals("the region retains its source user: " + request.group(), 0,
                        Integer.parseInt(request.group(1)));
                seen = true;
            }
        }
        assertTrue("the region reached the guest: " + log, seen);
    }

    @Test
    public void aResolverSourceNeverLosesItsForeignUserBeforeDispatch() throws Exception {
        String installed = shell("pm install-existing --user " + sProfile + " "
                + InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName());
        assertTrue(installed, installed.contains("installed"));
        android.content.ContentResolver resolver = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getContentResolver();
        assertNotNull("same-user source positive control", android.graphics.ImageDecoder.decodeBitmap(
                android.graphics.ImageDecoder.createSource(resolver, UserImageProvider.IMAGE)));
        android.net.Uri uri = android.content.ContentProvider.maybeAddUserId(UserImageProvider.IMAGE, sProfile);
        try {
            android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(resolver, uri));
            org.junit.Assert.fail("a remote snapshot cannot authenticate its foreign source user");
        } catch (java.io.IOException expected) {
            // Refused before VM admission until this source can be acquired by the broker itself.
        }
    }

    @Test
    public void aWrappedAcquiredClientKeepsItsForeignSourceUser() throws Exception {
        final android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final String installed = shell("pm install-existing --user " + sProfile + " " + context.getPackageName());
        assertTrue(installed, installed.contains("installed"));
        final android.content.ContentResolver resolver = context.getContentResolver();
        try (android.content.ContentProviderClient own = resolver.acquireContentProviderClient(UserImageProvider.IMAGE)) {
            assertNotNull(own);
            assertNotNull("same-user acquired-client positive control", android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(android.content.ContentResolver.wrap(own),
                            UserImageProvider.IMAGE)));
        }
        final android.net.Uri workUri = android.content.ContentProvider.maybeAddUserId(UserImageProvider.IMAGE, sProfile);
        try (android.content.ContentProviderClient work = resolver.acquireContentProviderClient(workUri)) {
            assertNotNull(work);
            try (android.content.res.AssetFileDescriptor raw = work.openAssetFile(UserImageProvider.IMAGE, "r")) {
                assertNotNull("the acquired work endpoint can open its unqualified URI", raw);
                try (java.io.InputStream input = raw.createInputStream()) {
                    assertArrayEquals("the work source is readable before the gate refuses its foreign label",
                            new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}, input.readNBytes(8));
                }
            }
            try {
                android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(
                        android.content.ContentResolver.wrap(work), UserImageProvider.IMAGE));
                org.junit.Assert.fail("the acquired work-user capability cannot become this process's source");
            } catch (java.io.IOException expected) {
                // The captured foreign source cannot be authenticated by a remote caller-supplied FD.
            }
        }
    }

    @Test
    public void aFileUriDoesNotBorrowTheResolversUser() throws Exception {
        final android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final String installed = shell("pm install-existing --user " + sProfile + " " + context.getPackageName());
        assertTrue(installed, installed.contains("installed"));
        final java.io.File file = java.io.File.createTempFile("source-user", ".png", context.getCacheDir());
        try {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) { out.write(png()); }
            final android.content.ContentResolver work = context.createContextAsUser(
                    android.os.UserHandle.of(sProfile), 0).getContentResolver();
            try (DecodeGate.OwnerScope scope = DecodeGate.ownerScope(0)) {
                assertNotNull("file URIs use this process's file access and captured scope",
                        android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(
                                work, android.net.Uri.fromFile(file))));
            }
        } finally {
            file.delete();
        }
    }

    static String shell(String command) throws Exception {
        final ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(command);
        try (java.io.InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            return new String(in.readAllBytes());
        }
    }
}
