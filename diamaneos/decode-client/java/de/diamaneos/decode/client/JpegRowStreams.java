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
 * size. Each band takes its rows in order from the handle's stripes, the last two rows kept for its overlap. The
 * stream and the service's request end after the last band's clean end, at an empty band, the operation's end, the
 * caller's death and any error.
 */
public final class JpegRowStreams {
    private final class Stream implements IBinder.DeathRecipient {
        final String key;
        IDecodeService service;
        Thumbnail reply;
        android.graphics.ColorSpace space;
        IBinder client;
        boolean busy, closed;
        int made, index, at; // rows taken, the next stripe, the place in the current one
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
    private final android.os.Handler mTimers = com.android.internal.os.BackgroundThread.getHandler();

    /** One request of a provider task ending at {@code end}, its callback {@code client}: the reply. */
    public Bundle take(Context context, ParcelFileDescriptor fd, String lane, int user, int forUid, int uid, int pid,
            Bundle extras, long end, IBinder client) {
        final Bundle reply = new Bundle();
        Stream s = null;
        try (VmDecode.ForApp app = VmDecode.forApp(forUid)) {
            final int width = extras.getInt(DecodeGate.EXTRA_WIDTH), height = extras.getInt(DecodeGate.EXTRA_HEIGHT);
            final int top = extras.getInt(DecodeGate.EXTRA_TOP), bottom = extras.getInt(DecodeGate.EXTRA_LEFT);
            final String key = key(fd, user, forUid, uid, pid, extras);
            final boolean taken;
            synchronized (mStreams) {
                s = mStreams.get(key);
                if (s == null && top == 0 && bottom > 0) mStreams.put(key, s = new Stream(key));
                taken = s != null && !s.busy && bottom > 0;
                if (taken) s.busy = true;
            }
            if (!taken) throw new VmDecode.RejectedException("no row stream to continue");
            if (s.service == null) start(context, s, fd, lane, user, width, height, extras, client, end);
            final Bitmap band = band(s, top, bottom, end);
            if (s.made == height) {
                // The last band counts only with the decode's clean end.
                final byte[] last = s.service.readStripe(s.reply.handle, s.index);
                if (last == null || last.length != 1 || (last[0] != 0 && last[0] != 2 && last[0] != 3)) {
                    DecodeMemory.recycle(band, null);
                    throw new VmDecode.RejectedException("row stream did not end cleanly");
                }
                reply.putInt(DecodeGate.EXTRA_SOURCE_ERROR, last[0]);
                close(s);
            }
            reply.putParcelable(DecodeGate.EXTRA_BITMAP, band);
            synchronized (mStreams) {
                s.busy = false;
            }
        } catch (VmDecode.RejectedException e) {
            close(s);
            reply.putBoolean(DecodeGate.EXTRA_REFUSED, true);
            reply.putString(DecodeGate.EXTRA_ERROR, "refused: " + e.getMessage());
        } catch (VmDecode.UnavailableException | RemoteException | android.system.ErrnoException
                | RuntimeException | OutOfMemoryError e) {
            close(s);
            reply.putString(DecodeGate.EXTRA_ERROR, "rows unavailable: " + e);
        }
        return reply;
    }

    /** An empty band, outside the provider's queue and its byte charge: the caller's stream ends now. */
    public void end(ParcelFileDescriptor fd, int user, int forUid, int uid, int pid, Bundle extras) {
        Stream s;
        try {
            final String key = key(fd, user, forUid, uid, pid, extras);
            synchronized (mStreams) {
                s = mStreams.get(key);
            }
        } catch (android.system.ErrnoException e) {
            return;
        }
        close(s);
    }

    // The stream's binding: the caller, the app decoded for, the owner user, the sealed file, the size and its number.
    private static String key(ParcelFileDescriptor fd, int user, int forUid, int uid, int pid, Bundle extras)
            throws android.system.ErrnoException {
        final android.system.StructStat file = android.system.Os.fstat(fd.getFileDescriptor());
        return uid + "/" + pid + "/" + forUid + "/" + user + "/" + file.st_dev + "/" + file.st_ino + "/"
                + extras.getInt(DecodeGate.EXTRA_WIDTH) + "x" + extras.getInt(DecodeGate.EXTRA_HEIGHT) + "#"
                + extras.getLong(DecodeGate.EXTRA_STREAM);
    }

    // The service's handle of the whole image, checked, then the stream's end at the operation's end and its death.
    private void start(Context context, Stream s, ParcelFileDescriptor fd, String lane, int user, int width,
            int height, Bundle extras, IBinder client, long end)
            throws VmDecode.UnavailableException, VmDecode.RejectedException, RemoteException {
        final DecodeRequest request = VmDecode.request(fd, lane, user, IDecodeService.PRIORITY_USER_WAITING);
        request.jpegRows = true;
        s.reply = VmDecode.client(context).call(IDecodeService.PRIORITY_USER_WAITING, end, request.ownerPackage,
                request.ownerPackage, 0, service -> {
            final Thumbnail t = service.decodeRegion(request, 0, 0, width, height, 1);
            if (t == null) throw new VmDecode.RejectedException("no reply");
            VmDecode.checkStatus(t.status);
            s.service = service;
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
    // opaque), read only before the band's deadline.
    private static Bitmap band(Stream s, int top, int bottom, long end)
            throws RemoteException, VmDecode.RejectedException, VmDecode.UnavailableException {
        final int stride = s.reply.width * 4;
        if (top > s.made || s.made - top > 2 || bottom <= s.made || bottom > s.reply.height
                || (long) (bottom - top) * s.reply.width > IDecodeService.MAX_PIXELS) {
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
        if (s.service != null) mTimers.post(() -> {
            try {
                s.service.release(s.reply.handle);
            } catch (RemoteException | RuntimeException e) {
                // The service is gone, and its handles with it.
            }
        });
    }
}
