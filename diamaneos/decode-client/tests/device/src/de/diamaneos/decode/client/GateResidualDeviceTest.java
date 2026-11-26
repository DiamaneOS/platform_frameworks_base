/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;
import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.system.Os;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class GateResidualDeviceTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    @After public void cleanup() {
        DecodeGate.setInputFaultForTest(null);
        DecodeGate.setForcedForTest(null);
        DecodeGate.setWaitForTest(null);
        DecodeGate.setLocal(null);
        DecodeGate.resetForTest();
        DecodeMemory.setMaxForTest(0);
        ProbeProvider.ICONS.clear();
        ProbeProvider.TIMINGS.clear();
    }
    private byte[] png() {
        Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.BLUE);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        bitmap.recycle();
        return out.toByteArray();
    }
    @Test public void aPublicFileOpenUsesTheEntryDeadline() throws Exception {
        fileEntry(false);
    }
    @Test public void aZeroWaitFileEntryDoesNotLeaveTheWriter() throws Exception {
        fileEntry(true);
    }
    private void fileEntry(boolean zeroWait) throws Exception {
        final android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        final String uid;
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.getUiAutomation().executeShellCommand("id -u"))) {
            uid = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        }
        assertEquals("the controlled public-file carrier must be root", "0", uid);
        final String source = context.getApplicationInfo().sourceDir;
        final String pkg = context.getPackageName();
        assertTrue(source.matches("[A-Za-z0-9_./=+!~:-]+"));
        assertTrue(pkg.matches("[A-Za-z0-9._]+"));
        assertNotNull(PublicFileEntryProbe.class.getMethod("main", String[].class));
        final String command = "/system/bin/env CLASSPATH=" + source
                + " /system/bin/app_process /system/bin " + PublicFileEntryProbe.class.getName()
                + " " + pkg + " " + zeroWait;
        Log.i("GateResidual", "public-file command=" + command);
        final String output;
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.getUiAutomation().executeShellCommand(command))) {
            output = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        Log.i("GateResidual", output);
        assertTrue(output, output.contains("PUBLIC-FILE-PROBE PASS"));
    }
    @Test public void aServerUriIconUsesTheEntryDeadline() throws Exception {
        final String key = "server-slow-" + SystemClock.elapsedRealtimeNanos();
        final java.util.concurrent.atomic.AtomicLongArray timing = new java.util.concurrent.atomic.AtomicLongArray(4);
        ProbeProvider.ICONS.put(key, png());
        ProbeProvider.TIMINGS.put(key, timing);
        Uri uri = Uri.parse("content://" + ProbeProvider.AUTHORITY + "/icon-late/" + key);
        context.grantUriPermission("android", uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            Bundle request = new Bundle();
            request.putString("uri", uri.toString());
            long start = SystemClock.uptimeMillis();
            Bundle answer = context.getContentResolver().call(DecodeGate.URI, "testUriIcon", null, request);
            long entryReturn = SystemClock.uptimeMillis() - start;
            assertNotNull("server test entry unavailable", answer);
            final long end = SystemClock.uptimeMillis() + ProbeProvider.LATE_MS + 5000;
            while (timing.get(1) == 0 && SystemClock.uptimeMillis() < end) SystemClock.sleep(10);
            assertTrue("the named provider was never opened", timing.get(0) > 0);
            final long providerDelay = timing.get(1) - timing.get(0);
            assertEquals("provider caller UID", android.os.Process.SYSTEM_UID, timing.get(2));
            final String serverPid;
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(
                    InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("pidof system_server"))) {
                serverPid = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            }
            assertTrue("system_server PID unavailable", serverPid.matches("[0-9]+"));
            assertEquals("provider caller PID", Long.parseLong(serverPid), timing.get(3));
            assertTrue("the provider did not complete its slow open", providerDelay >= ProbeProvider.LATE_MS);
            Log.i("GateResidual", "server URI icon entry-return ms=" + entryReturn + " inner="
                    + answer.getLong("elapsed") + " actual provider delay=" + providerDelay);
            assertTrue(entryReturn <= DecodeGate.BINDER_MS + 250);
        } finally {
            context.revokeUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ProbeProvider.TIMINGS.remove(key);
            ProbeProvider.ICONS.remove(key);
        }
    }
    @Test public void aReaderAllocationFailureRollsBackAndRefills() throws Exception {
        DecodeGate.setForcedForTest(true);
        DecodeGate.setLocal(new FakeGateProvider(0));
        byte[] image = java.util.Arrays.copyOf(png(), (1 << 20) + 1);
        AtomicBoolean once = new AtomicBoolean();
        AtomicInteger permits = new AtomicInteger();
        OutOfMemoryError injected = new OutOfMemoryError("staging construction");
        DecodeGate.setInputFaultForTest(() -> {
            if (Thread.currentThread().getName().equals("DecodeGateInput") && once.compareAndSet(false, true)) {
                permits.set(DecodeGate.waitingForTest());
                throw injected;
            }
        });
        AtomicReference<GateRefill> registration = new AtomicReference<>();
        AtomicReference<Bitmap> result = new AtomicReference<>();
        CountDownLatch filled = new CountDownLatch(1);
        GateRefill refill = new GateRefill(Runnable::run, () -> {
            Bitmap bitmap = registration.get().load(() -> BitmapFactory.decodeByteArray(image, 0, image.length));
            if (bitmap != null) { result.set(bitmap); filled.countDown(); }
        });
        registration.set(refill);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                assertNull(refill.load(() -> BitmapFactory.decodeByteArray(image, 0, image.length))));
        assertTrue("input waited without the operation permit", permits.get() > 0);
        assertTrue("reader failure ended its refill", filled.await(12, TimeUnit.SECONDS));
        assertNotNull(result.get());
        DecodeMemory.recycle(result.get(), null);
        refill.cancel();
        DecodeGate.resetForTest();
        assertEquals("reader slots", 0, DecodeGate.inputReadsForTest());
        assertEquals("wait permits", 0, DecodeGate.waitingForTest());
    }
    @Test public void aMutableInputCannotBeRetainedByTheBroker() throws Exception {
        File file = new File(context.getCacheDir(), "mutable-gate-input");
        try (OutputStream out = new FileOutputStream(file)) { out.write(png()); }
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
            Bundle extras = new Bundle();
            extras.putParcelable(DecodeGate.EXTRA_FD, fd);
            extras.putInt(DecodeGate.EXTRA_OWNER_USER, context.getUserId());
            extras.putInt(DecodeGate.EXTRA_WIDTH, 32);
            extras.putInt(DecodeGate.EXTRA_HEIGHT, 32);
            extras.putBinder(DecodeGate.EXTRA_CALLBACK, new IRemoteCallback.Stub() {
                @Override public void sendResult(Bundle result) { }
            }.asBinder());
            Bundle answer = context.getContentResolver().call(DecodeGate.URI, DecodeGate.METHOD_DECODE, null, extras);
            assertFalse(answer.getBoolean(DecodeGate.EXTRA_TAKEN));
            assertTrue(answer.getBoolean(DecodeGate.EXTRA_REFUSED));
        } finally { file.delete(); }
    }
}
