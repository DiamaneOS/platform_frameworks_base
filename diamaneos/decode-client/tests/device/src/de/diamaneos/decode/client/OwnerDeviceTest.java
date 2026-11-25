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
    private static boolean sMade;
    // Another start in every run: the service keeps answers per owner across runs.
    private static int sSeed = (int) (System.nanoTime() & 0xffff);

    @BeforeClass
    public static void workProfile() throws Exception {
        // A run that already has a work profile names it (-e workUser N): a second managed profile cannot be made.
        final String given = InstrumentationRegistry.getArguments().getString("workUser");
        if (given != null) {
            sProfile = Integer.parseInt(given);
            return;
        }
        // -e profileType private: a Private Space instead of a work profile.
        final boolean privateSpace = "private".equals(
                InstrumentationRegistry.getArguments().getString("profileType"));
        final String made = shell("pm create-user --profileOf 0 "
                + (privateSpace ? "--user-type android.os.usertype.profile.PRIVATE" : "--managed") + " owner-test");
        final Matcher m = Pattern.compile("created user id (\\d+)").matcher(made);
        assertTrue("no work profile made: " + made, m.find());
        sProfile = Integer.parseInt(m.group(1));
        sMade = true;
        Log.i(TAG, "work profile " + sProfile + ": " + shell("am start-user -w " + sProfile).trim());
    }

    @AfterClass
    public static void removeProfile() throws Exception {
        if (sMade) {
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
        assertNotNull("decoded for the profile this package may act for", decodeAs(sProfile, png));
        assertEquals("another owner: never the answers kept for user 0", again, DecodeGate.countsForTest()[4]);
    }

    @Test
    public void aUserTheCallerMayNotActForIsRefused() {
        assertNull("no such user: refused, never decoded as this app's", decodeAs(sProfile + 500, png()));
    }

    @Test
    public void aCallerThatMayActForTheProfile_namesItForItsOwnBytes() throws Exception {
        // This package holds INTERACT_ACROSS_USERS_FULL: as Android lets it act for the profile, its own bytes go as
        // the profile's image (a caller without the permission is refused, host checks h1_cross_users).
        final double since = System.currentTimeMillis() / 1000.0;
        for (int user : new int[] {0, sProfile, sProfile, 0}) {
            assertNotNull("decoded as user " + user, decodeAs(user, png()));
        }
        final String log = shell(String.format(java.util.Locale.ROOT,
                "logcat -d -v epoch -T %.3f -s DecoderUsers:I", since));
        final Matcher m = REQUEST.matcher(log);
        boolean own = false;
        boolean profile = false;
        while (m.find()) {
            if (m.group(4).equals("?gate:uid" + Process.myUid())) {
                own |= Integer.parseInt(m.group(1)) == 0;
                profile |= Integer.parseInt(m.group(1)) == sProfile;
            }
        }
        assertTrue("both users' requests reached the guest in this app's lane: " + log, own && profile);
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
    public void aForeignResolverSourceIsOpenedWithTheCallersRights() throws Exception {
        String installed = shell("pm install-existing --user " + sProfile + " "
                + InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName());
        assertTrue(installed, installed.contains("installed"));
        android.content.ContentResolver resolver = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getContentResolver();
        assertNotNull("same-user source positive control", android.graphics.ImageDecoder.decodeBitmap(
                android.graphics.ImageDecoder.createSource(resolver, UserImageProvider.IMAGE)));
        android.net.Uri uri = android.content.ContentProvider.maybeAddUserId(UserImageProvider.IMAGE, sProfile);
        final double since = System.currentTimeMillis() / 1000.0;
        // As Android opens it: this process opens the profile's URI with its own rights and names the profile.
        assertNotNull("the profile's image", android.graphics.ImageDecoder
                .decodeBitmap(android.graphics.ImageDecoder.createSource(resolver, uri)));
        assertForeignRequestReachedTheGuest(since);
    }

    @Test
    public void anotherUsersPhotoComesThroughTheSystemServer() throws Exception {
        final android.os.UserManager users = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSystemService(android.os.UserManager.class);
        final Bitmap photo = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        final int colour = Color.rgb(200, 30 + sSeed % 100, 60);
        photo.eraseColor(colour);
        users.setUserIcon(sProfile, photo);
        final double since = System.currentTimeMillis() / 1000.0;
        final Bitmap icon = users.getUserIcon(sProfile);
        assertNotNull("UserManager.getUserIcon of the profile, decoded as the profile's image", icon);
        assertEquals("the photo's pixels", colour, icon.getPixel(icon.getWidth() / 2, icon.getHeight() / 2));
        assertForeignRequestReachedTheGuest(since);
    }

    @Test
    public void aForeignRequestIsTakenOnlyAsTheCallerMayAct() throws Exception {
        assertTrue("its own bytes named for the profile it may act for", foreignCall(null));
        assertFalse("a URI source: URIs come as the caller's own descriptor", foreignCall("uri-image:content://"
                + sProfile + "@com.android.contacts/contacts"));
        assertFalse("another user's photo than the one named", foreignCall("usericon:0"));
        assertFalse("not a content URI", foreignCall("uri:file:///data/system/users/" + sProfile + "/photo.png"));
    }

    // A direct request to the provider naming the profile, with this process's own bytes: whether it was taken.
    private static boolean foreignCall(String source) throws Exception {
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try (ParcelFileDescriptor write = pipe[1]) {
            final android.os.Bundle extras = new android.os.Bundle();
            extras.putParcelable(DecodeGate.EXTRA_FD, pipe[0]);
            extras.putInt(DecodeGate.EXTRA_WIDTH, 16);
            extras.putInt(DecodeGate.EXTRA_HEIGHT, 16);
            extras.putInt(DecodeGate.EXTRA_OWNER_USER, sProfile);
            if (source != null) extras.putString(DecodeGate.EXTRA_SOURCE, source);
            extras.putBinder(DecodeGate.EXTRA_CALLBACK, new android.os.IRemoteCallback.Stub() {
                @Override
                public void sendResult(android.os.Bundle result) {
                }
            }.asBinder());
            final android.os.Bundle reply = InstrumentationRegistry.getInstrumentation().getTargetContext()
                    .getContentResolver().call(DecodeGate.URI, DecodeGate.METHOD_DECODE, null, extras);
            return reply != null && reply.getBoolean(DecodeGate.EXTRA_TAKEN);
        } finally {
            pipe[0].close();
        }
    }

    // The guest saw a request of the profile's user in this app's lane after {@code since}.
    private static void assertForeignRequestReachedTheGuest(double since) throws Exception {
        final String log = shell(String.format(java.util.Locale.ROOT,
                "logcat -d -v epoch -T %.3f -s DecoderUsers:I", since));
        final Matcher request = REQUEST.matcher(log);
        boolean seen = false;
        while (request.find()) {
            seen |= Integer.parseInt(request.group(1)) == sProfile
                    && request.group(4).equals("?gate:uid" + Process.myUid());
        }
        assertTrue("a request of user " + sProfile + " in this app's lane reached the guest: " + log, seen);
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
                    assertArrayEquals("the work source is readable by this process",
                            new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}, input.readNBytes(8));
                }
            }
            // The captured work user travels with the request: this process's descriptor of the work endpoint is
            // decoded as the work profile's image.
            final double since = System.currentTimeMillis() / 1000.0;
            assertNotNull("the work source", android.graphics.ImageDecoder
                    .decodeBitmap(android.graphics.ImageDecoder.createSource(
                            android.content.ContentResolver.wrap(work), UserImageProvider.IMAGE)));
            assertForeignRequestReachedTheGuest(since);
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
