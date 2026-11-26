// Copyright (C) 2026 DiamaneOS
package de.diamaneos.decode.peer;

import static org.junit.Assert.*;
import android.app.AppGlobals;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.DecodeGate;
import android.net.Uri;
import android.os.Bundle;
import android.os.IRemoteCallback;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class PeerUserDeviceTest {
    private static final String FIXTURE = "de.diamaneos.archive.icon.fixture";
    private static int sProfile = -1;
    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }
    private static String shell(String command) throws Exception {
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command))) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    @BeforeClass public static void makeProfile() throws Exception {
        assertEquals("a separate system-UID process", 1000, Process.myUid());
        String made = shell("pm create-user --profileOf 0 --managed peer-user-test");
        Matcher m = Pattern.compile("created user id (\\d+)").matcher(made);
        assertTrue(made, m.find());
        sProfile = Integer.parseInt(m.group(1));
        assertTrue(shell("am start-user -w " + sProfile).contains("Success"));
    }
    @AfterClass public static void removeProfile() throws Exception {
        if (sProfile > 0) shell("pm remove-user " + sProfile);
    }
    @Test public void aForeignLabelOnAnUnverifiedPeerFdIsRefused() throws Exception {
        Bundle reply = request(sProfile);
        assertFalse("the system peer supplied its own bytes, not a trusted work-user source",
                reply.getBoolean(DecodeGate.EXTRA_TAKEN));
    }
    @Test public void theSamePeerCanSubmitItsOwnUsersFd() throws Exception {
        assertTrue("positive control of the same FD route", request(0).getBoolean(DecodeGate.EXTRA_TAKEN));
    }
    private static Bundle request(int user) throws Exception {
        byte[] png = android.util.Base64.decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGD4DwABBAEAX+XDSwAAAABJRU5ErkJggg==", 0);
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) { out.write(png); }
        try (ParcelFileDescriptor fd = pipe[0]) {
            CountDownLatch done = new CountDownLatch(1);
            Bundle[] completed = new Bundle[1];
            Bundle extras = new Bundle();
            extras.putParcelable(DecodeGate.EXTRA_FD, fd);
            extras.putInt(DecodeGate.EXTRA_OWNER_USER, user);
            extras.putLong(DecodeGate.EXTRA_TIME, 5000);
            extras.putBinder(DecodeGate.EXTRA_CALLBACK, new IRemoteCallback.Stub() {
                @Override public void sendResult(Bundle result) {
                    completed[0] = result;
                    done.countDown();
                }
            }.asBinder());
            Bundle reply = context().getContentResolver().call(DecodeGate.URI, DecodeGate.METHOD_HEADER,
                    null, extras);
            assertNotNull(reply);
            if (reply.getBoolean(DecodeGate.EXTRA_TAKEN)) {
                assertTrue("accepted work settles before the next test", done.await(10, TimeUnit.SECONDS));
                assertNotNull(completed[0]);
                assertNull("the own-source positive control returns a valid header",
                        completed[0].getString(DecodeGate.EXTRA_ERROR));
                assertEquals(1, completed[0].getInt(DecodeGate.EXTRA_WIDTH));
                assertEquals(1, completed[0].getInt(DecodeGate.EXTRA_HEIGHT));
            }
            return reply;
        }
    }
}
