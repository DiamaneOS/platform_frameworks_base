/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.DecodeGate;
import android.graphics.DecodeMemory;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;

import de.diamaneos.sanitizer.decode.DecodeChecks;
import de.diamaneos.sanitizer.decode.DecodeRequest;
import de.diamaneos.sanitizer.decode.IDecodeService;
import de.diamaneos.sanitizer.decode.Thumbnail;

/**
 * DiamaneOS: one pass over a large plain JPEG for the decode gate's provider ({@link DecodeGate#METHOD_JPEG_ROWS}).
 * Its first band sends the input to the decode service once. The service's handle stays here under the caller's
 * stream number, bound to its uid and pid, the app decoded for, the owner user, the sealed descriptor's file and the
 * size. Each band takes its rows in order from the handle's stripes, the last two rows kept for its overlap, and the
 * caller marks its last band (EXTRA_SAMPLE 2), which may lie wholly in the kept rows. The stream and the service's
 * request end after the last band and the decode's clean end, at an empty band, the operation's end, a band's end,
 * the caller's death and any error. Timers and releases run on a thread of the stream's own.
 */
public final class JpegRowStreams {
    private final class Stream implements IBinder.DeathRecipient {
        final String key;
        IDecodeService service;
        Thumbnail reply;
        android.graphics.ColorSpace space;
        IBinder client;
        boolean busy, closed, released;
        int made, index, at, sourceError = -1; // rows taken, the next stripe, its place, the decode's clean end
        byte[] stripe = new byte[0];
        final byte[][] last = new byte[2][]; // row y at y % 2

        Stream(String key) {
            this.key = key;
        }

        @Override
        public void binderDied() {
            close(this);
        }
    }

    private final java.util.HashMap<String, Stream> mStreams = new java.util.HashMap<>(); // guarded by itself
    private final android.os.Handler mTimers;

    public JpegRowStreams() {
        final android.os.HandlerThread thread = new android.os.HandlerThread("JpegRowStreams");
        thread.start();
        mTimers = new android.os.Handler(thread.getLooper());
    }

    /** Whether {@code key} names a stream under way: its next band needs no start. */
    public boolean continues(String key) {
        synchronized (mStreams) {
            final Stream s = mStreams.get(key);
            return s != null && s.service != null;
        }
    }

    /**
     * One band of the stream {@code key} (JpegRowStreams.key) for a provider task ending at {@code end}, the start's
     * descriptor {@code fd} (null to continue a stream) and callback {@code client}: the reply.
     */
    public Bundle take(Context context, ParcelFileDescriptor fd, String key, String lane, int user, int forUid,
            Bundle extras, long end, IBinder client) {
        final Bundle reply = new Bundle();
        Stream s = null;
        final Object timer = new Object();
        try (VmDecode.ForApp app = VmDecode.forApp(forUid)) {
            final int width = extras.getInt(DecodeGate.EXTRA_WIDTH), height = extras.getInt(DecodeGate.EXTRA_HEIGHT);
            final int top = extras.getInt(DecodeGate.EXTRA_TOP), bottom = extras.getInt(DecodeGate.EXTRA_LEFT);
            final boolean lastBand = extras.getInt(DecodeGate.EXTRA_SAMPLE) == 2;
            final boolean taken;
            synchronized (mStreams) {
                s = mStreams.get(key);
                if (s == null && top == 0 && bottom > 0 && fd != null) mStreams.put(key, s = new Stream(key));
                taken = s != null && !s.busy && bottom > 0;
                if (taken) s.busy = true;
            }
            if (!taken) throw new VmDecode.RejectedException("no row stream to continue");
            final Stream band = s;
            mTimers.postAtTime(() -> close(band), timer, end); // a band's end, also for a stripe read under way
            if (s.service == null) start(context, s, fd, lane, user, width, height, extras, client, end);
            final Bitmap pixels = band(s, top, bottom, lastBand, end);
            if (s.made == height && s.sourceError < 0) {
                // The rows end only with the decode's clean end. The handle goes at once, the kept rows stay.
                final byte[] last = s.service.readStripe(s.reply.handle, s.index);
                if (last == null || last.length != 1 || (last[0] != 0 && last[0] != 2 && last[0] != 3)) {
                    DecodeMemory.recycle(pixels, null);
                    throw new VmDecode.RejectedException("row stream did not end cleanly");
                }
                s.sourceError = last[0];
                release(s);
            }
            if (lastBand) {
                reply.putInt(DecodeGate.EXTRA_SOURCE_ERROR, s.sourceError);
                close(s);
            }
            reply.putParcelable(DecodeGate.EXTRA_BITMAP, pixels);
            synchronized (mStreams) {
                s.busy = false;
            }
        } catch (VmDecode.RejectedException e) {
            close(s);
            reply.putBoolean(DecodeGate.EXTRA_REFUSED, true);
            reply.putString(DecodeGate.EXTRA_ERROR, "refused: " + e.getMessage());
        } catch (VmDecode.UnavailableException | RemoteException | RuntimeException | OutOfMemoryError e) {
            close(s);
            reply.putString(DecodeGate.EXTRA_ERROR, "rows unavailable: " + e);
        } finally {
            mTimers.removeCallbacksAndMessages(timer);
        }
        return reply;
    }

    /** An empty band, outside the provider's queue and its byte charge: the stream {@code key} ends now. */
    public void end(String key) {
        final Stream s;
        synchronized (mStreams) {
            s = mStreams.get(key);
        }
        close(s);
    }

    /**
     * A stream's binding: the caller, the app decoded for, the owner user, the sealed file, the size and the caller's
     * number. Null when the descriptor cannot be read.
     */
    public static String key(ParcelFileDescriptor fd, int user, int forUid, int uid, int pid, Bundle extras) {
        try {
            final android.system.StructStat file = android.system.Os.fstat(fd.getFileDescriptor());
            return uid + "/" + pid + "/" + forUid + "/" + user + "/" + file.st_dev + "/" + file.st_ino + "/"
                    + extras.getInt(DecodeGate.EXTRA_WIDTH) + "x" + extras.getInt(DecodeGate.EXTRA_HEIGHT) + "#"
                    + extras.getLong(DecodeGate.EXTRA_STREAM);
        } catch (android.system.ErrnoException e) {
            return null;
        }
    }

    // The service's handle of the whole image, checked, then the stream's end at the operation's end and its death.
    // The handle becomes the stream's in the call itself: a call whose caller left (its time ran out, the stream
    // ended meanwhile) lets it go at once, never leaving a request in the service.
    private void start(Context context, Stream s, ParcelFileDescriptor fd, String lane, int user, int width,
            int height, Bundle extras, IBinder client, long end)
            throws VmDecode.UnavailableException, VmDecode.RejectedException, RemoteException {
        final DecodeRequest request = VmDecode.request(fd, lane, user, IDecodeService.PRIORITY_USER_WAITING);
        request.jpegRows = true;
        VmDecode.client(context).call(IDecodeService.PRIORITY_USER_WAITING, end, request.ownerPackage,
                request.ownerPackage, 0, service -> {
            final Thumbnail t = service.decodeRegion(request, 0, 0, width, height, 1);
            if (t == null) throw new VmDecode.RejectedException("no reply");
            VmDecode.checkStatus(t.status);
            final boolean kept;
            synchronized (mStreams) {
                kept = !s.closed;
                if (kept) {
                    s.service = service;
                    s.reply = t;
                }
            }
            if (!kept) {
                service.release(t.handle);
                throw new VmDecode.UnavailableException("the stream ended while it started");
            }
            return t;
        });
        final Thumbnail t = s.reply;
        if (t.width != width || t.height != height || t.stripeRows != IDecodeService.MAX_STRIPE_BYTES / (width * 4)
                || t.rotation != 0 || t.mirrored || t.pixelFormat != 0 || t.gainmapWidth != 0 || t.gainmapHeight != 0
                || t.gainmapOnly || t.sourceError != 0 || !DecodeChecks.isColorSpace(t.colorSpace, t.colorDescription)) {
            throw new VmDecode.RejectedException("row stream reply breaks the contract");
        }
        s.space = android.graphics.GateColorSpaces.of(t.colorSpace, t.colorDescription);
        mTimers.postAtTime(() -> close(s), s, SystemClock.uptimeMillis() + Math.max(1,
                Math.min(DecodeGate.WORKER_MS, extras.getLong(DecodeGate.EXTRA_OPERATION_TIME))));
        s.client = client;
        client.linkToDeath(s, 0);
    }

    // Rows top to bottom: the two kept rows, then the stripes in order (each checked as the contract checks one, and
    // opaque), read only before the band's deadline. The caller's last band may lie wholly in the kept rows.
    private static Bitmap band(Stream s, int top, int bottom, boolean lastBand, long end)
            throws RemoteException, VmDecode.RejectedException, VmDecode.UnavailableException {
        final int stride = s.reply.width * 4, height = s.reply.height;
        if (top > s.made || s.made - top > 2 || bottom > height || bottom <= top
                || (bottom <= s.made && !(lastBand && bottom == height && s.made == height))
                || (lastBand && bottom != height) || (long) (bottom - top) * s.reply.width > IDecodeService.MAX_PIXELS) {
            throw new VmDecode.RejectedException("rows out of order");
        }
        try (DecodeMemory.Reservation memory = ReplyPixels.reserve((long) (bottom - top) * stride
                + 2L * IDecodeService.MAX_STRIPE_BYTES)) {
            final Bitmap band = Bitmap.createBitmap(s.reply.width, bottom - top, Bitmap.Config.ARGB_8888, true,
                    s.space);
            try {
                try (ReplyPixels.Rows out = new ReplyPixels.Rows(band, s.space, false)) {
                    for (int y = top; y < bottom; y++) {
                        if (y == s.made) {
                            if (s.at == s.stripe.length) {
                                if (SystemClock.uptimeMillis() >= end) throw new VmDecode.UnavailableException("late");
                                s.stripe = s.service.readStripe(s.reply.handle, s.index);
                                final String bad = DecodeChecks.check(s.reply, s.index++, s.stripe);
                                if (bad != null) throw new VmDecode.RejectedException("row stripe: " + bad);
                                for (int i = 3; i < s.stripe.length; i += 4) {
                                    if (s.stripe[i] != (byte) 255) throw new VmDecode.RejectedException("not opaque");
                                }
                                s.at = 0;
                            }
                            s.last[y % 2] = java.util.Arrays.copyOfRange(s.stripe, s.at, s.at += stride);
                            s.made++;
                        }
                        out.put(y - top, s.last[y % 2]);
                    }
                }
                DecodeMemory.keep(band, memory);
                return band;
            } catch (RemoteException | VmDecode.RejectedException | VmDecode.UnavailableException
                    | RuntimeException | OutOfMemoryError e) {
                DecodeMemory.recycle(band, null);
                throw e;
            }
        }
    }

    // Once: the stream leaves the table, its timer and death link go, and the service lets its handle go.
    private void close(Stream s) {
        synchronized (mStreams) {
            if (s == null || s.closed) return;
            s.closed = true;
            mStreams.remove(s.key);
        }
        mTimers.removeCallbacksAndMessages(s);
        try {
            if (s.client != null) s.client.unlinkToDeath(s, 0);
        } catch (RuntimeException e) {
            // Not linked.
        }
        release(s);
    }

    // The service lets the handle go, once, on the stream's own thread: no caller's thread waits for it.
    private void release(Stream s) {
        synchronized (mStreams) {
            if (s.service == null || s.released) return;
            s.released = true;
        }
        mTimers.post(() -> {
            try {
                s.service.release(s.reply.handle);
            } catch (RemoteException | RuntimeException e) {
                // The service is gone, and its handles with it.
            }
        });
    }
}
