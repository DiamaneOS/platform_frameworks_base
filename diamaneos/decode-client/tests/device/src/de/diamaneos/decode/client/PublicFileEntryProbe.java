/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.DecodeGate;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Public filename acquisition on the controlled root test carrier. */
public class PublicFileEntryProbe {
    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 0 || args.length != 2) throw new AssertionError("root carrier and arguments");
        Looper.prepareMainLooper();
        Context system = android.app.ActivityThread.systemMain().getSystemContext();
        Context context = system.createPackageContext(args[0], Context.CONTEXT_IGNORE_SECURITY);
        boolean zeroWait = Boolean.parseBoolean(args[1]);
        new Thread(() -> {
            int result = 0;
            try {
                DecodeGate.setForcedForTest(false);
                Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.BLUE);
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded)) {
                    throw new AssertionError("synthetic PNG");
                }
                bitmap.recycle();
                byte[] bytes = encoded.toByteArray();
                long nativeMs = entry(context, bytes, false, false);
                long gatedMs = entry(context, bytes, true, zeroWait);
                System.out.println("PUBLIC-FILE native-entry-ms=" + nativeMs
                        + " entry-return-ms=" + gatedMs + " zeroWait=" + zeroWait + " writer-delay-ms=1500");
                if (nativeMs < 1350) throw new AssertionError("native positive control did not wait");
                if (gatedMs > (zeroWait ? 0 : DecodeGate.MAIN_MS) + 150) {
                    throw new AssertionError("public acquisition escaped the entry deadline");
                }
                System.out.println("PUBLIC-FILE-PROBE PASS");
            } catch (Throwable error) {
                result = 1;
                error.printStackTrace(System.out);
            } finally {
                DecodeGate.setForcedForTest(null);
                DecodeGate.setWaitForTest(null);
                DecodeGate.setLocal(null);
                DecodeGate.resetForTest();
            }
            System.exit(result);
        }, "public-file-entry-probe").start();
        Looper.loop();
    }

    private static long entry(Context context, byte[] bytes, boolean gated, boolean zeroWait) throws Exception {
        File fifo = File.createTempFile("gate-public-entry-", ".fifo", context.getCacheDir());
        Thread writer = null;
        FileDescriptor cleanup = null;
        try {
            if (!fifo.delete()) throw new AssertionError("remove initial regular fixture");
            Os.mkfifo(fifo.getPath(), 0600);
            writer = new Thread(() -> {
                SystemClock.sleep(1500);
                try (FileOutputStream output = new FileOutputStream(fifo)) { output.write(bytes); }
                catch (Exception ignored) { }
            }, "public-entry-writer");
            writer.setDaemon(true);
            DecodeGate.setForcedForTest(gated);
            DecodeGate.setWaitForTest(zeroWait ? 0L : null);
            DecodeGate.resetForTest();
            FutureTask<Long> call = new FutureTask<>(() -> {
                long start = SystemClock.uptimeMillis();
                Bitmap made = BitmapFactory.decodeFile(fifo.getPath());
                long elapsed = SystemClock.uptimeMillis() - start;
                try {
                    if (gated && made != null) throw new AssertionError("critical call waited for pixels");
                    if (!gated && made == null) throw new AssertionError("native fixture could not decode");
                } finally { if (made != null) made.recycle(); }
                return elapsed;
            });
            writer.start();
            new Handler(Looper.getMainLooper()).post(call);
            return call.get(8, TimeUnit.SECONDS);
        } finally {
            try {
                cleanup = Os.open(fifo.getPath(), OsConstants.O_RDONLY | OsConstants.O_NONBLOCK, 0);
                if (writer != null) {
                    writer.join(5000);
                    if (writer.isAlive()) throw new AssertionError("fixture writer remained");
                }
                long end = SystemClock.uptimeMillis() + 2000;
                while (DecodeGate.inputReadsForTest() != 0 && SystemClock.uptimeMillis() < end) {
                    SystemClock.sleep(10);
                }
                if (DecodeGate.inputReadsForTest() != 0 || DecodeGate.waitingForTest() != 0) {
                    throw new AssertionError("public acquisition retained slots or wait permits");
                }
            } finally {
                if (cleanup != null) Os.close(cleanup);
                if (fifo.exists() && !fifo.delete()) throw new AssertionError("remove owned FIFO");
            }
        }
    }
}
