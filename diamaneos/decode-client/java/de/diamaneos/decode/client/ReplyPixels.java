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

import android.graphics.BlendMode;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.DecodeMemory;
import android.graphics.Gainmap;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.RemoteException;

import de.diamaneos.sanitizer.decode.DecodeChecks;
import de.diamaneos.sanitizer.decode.Frames;
import de.diamaneos.sanitizer.decode.IDecodeService;
import de.diamaneos.sanitizer.decode.Thumbnail;

import java.nio.ByteBuffer;

/**
 * The pixels of a reply of the decode service, built from its stripes. Everything the service sent is hostile until
 * checked here. The memory the image takes in this process (the bitmap, its gain map, a turned copy) is reserved before
 * anything is allocated ({@link DecodeMemory}): a reply that does not fit now is a "no room now" answer, asked for
 * again shortly, one that could never fit is refused. The stripes go into the bitmap a block at a time (never the
 * whole image in one array), and before each transaction the call checks that its caller still waits: a caller that
 * gave up interrupted this call thread, the rest is not read and the handle is released by the caller of these
 * methods.
 */
final class ReplyPixels {
    // A stripe's bitmap besides the image: at most the service's stripe size.
    private static final long STRIPE_BYTES = IDecodeService.MAX_STRIPE_BYTES;

    private ReplyPixels() {
    }

    /** Reads every stripe of {@code t}, checks it, and builds the bitmap turned upright. */
    static VmDecode.Decoded thumbnail(IDecodeService service, Thumbnail t, int maxWidth, int maxHeight)
            throws RemoteException, VmDecode.RejectedException, VmDecode.UnavailableException {
        return thumbnail(service, t, maxWidth, maxHeight, false);
    }

    static VmDecode.Decoded thumbnail(IDecodeService service, Thumbnail t, int maxWidth, int maxHeight,
            boolean allowPartial) throws RemoteException, VmDecode.RejectedException, VmDecode.UnavailableException {
        return thumbnail(service, t, maxWidth, maxHeight, allowPartial, false);
    }

    static VmDecode.Decoded thumbnail(IDecodeService service, Thumbnail t, int maxWidth, int maxHeight,
            boolean allowPartial, boolean gainmapOnly)
            throws RemoteException, VmDecode.RejectedException, VmDecode.UnavailableException {
        final int width = t.width;
        final int height = t.height;
        if (maxWidth < 1 || maxHeight < 1) {
            throw new VmDecode.RejectedException("bad box " + maxWidth + "x" + maxHeight);
        }
        // The contract's own checks (DecodeChecks, the service's copy, compared at build time), then StripeValidator's.
        final String broken = DecodeChecks.check(t, maxWidth, maxHeight, gainmapOnly);
        if (broken != null) {
            throw new VmDecode.RejectedException("thumbnail breaks the contract: " + broken);
        }
        if (!allowPartial && t.sourceError != 0) {
            throw new VmDecode.RejectedException("partial image", t.sourceError);
        }
        final int stripes;
        try {
            stripes = StripeValidator.checkHeader(width, height, t.stripeRows, t.rotation, maxWidth, maxHeight, t.pixelFormat);
        } catch (StripeValidator.BadReplyException e) {
            throw new VmDecode.RejectedException(e.getMessage());
        }
        // A greyscale gain image's pixels (R = G = B, checked per stripe) become ALPHA_8, as Android's codec hands out
        // a Gray_8 gain map.
        final boolean grey = gainmapOnly && t.gainmapMono;
        final long image = (long) width * height * (grey ? 1 : DecodeChecks.bytesPerPixel(t.pixelFormat));
        final long gain = (long) t.gainmapWidth * t.gainmapHeight * 4;
        final boolean straight = (t.pixelFormat & 2) != 0;
        final int copies = straight || t.rotation != 0 ? 2 : 1; // a turn makes a turned copy of both
        try (DecodeMemory.Reservation memory = reserve(copies * (image + gain) + 2 * STRIPE_BYTES)) {
            // In the file's colour space, as Android's decode gives it: sRGB or Display P3 (Thumbnail.colorSpace).
            final Bitmap bitmap = grey ? Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
                    : Bitmap.createBitmap(width, height, (t.pixelFormat & 1) != 0
                    ? Bitmap.Config.RGBA_F16 : Bitmap.Config.ARGB_8888, true,
                    android.graphics.GateColorSpaces.of(t.colorSpace, t.colorDescription));
            Bitmap result = bitmap;
            try {
                if (straight) {
                    if (t.rotation != 0 || t.mirrored || t.gainmapWidth != 0 || t.gainmapHeight != 0) {
                        throw new VmDecode.RejectedException("turned straight pixels");
                    }
                    bitmap.setPremultiplied(false);
                }
                try (Rows rows = new Rows(bitmap, straight || grey)) {
                for (int i = 0; i < stripes; i++) {
                    stillWanted();
                    final byte[] stripe = service.readStripe(t.handle, i);
                    final String bad = DecodeChecks.check(t, i, stripe);
                    if (bad != null) {
                        throw new VmDecode.RejectedException("stripe " + i + " breaks the contract: " + bad);
                    }
                    try {
                        StripeValidator.checkStripe(width, height, t.stripeRows, i, stripe, t.pixelFormat);
                    } catch (StripeValidator.BadReplyException e) {
                        throw new VmDecode.RejectedException(e.getMessage());
                    }
                    rows.put(i * t.stripeRows, stripe);
                }
                }
                // Ultra HDR: the gain map, every stripe checked, attached as Android's decode attaches it. A rotation
                // below turns it with the image (Bitmap.createBitmap transforms the gain map too).
                if (t.gainmapWidth > 0) {
                    final Gainmap gainmap = gainmap(service, t);
                    if (gainmap != null) {
                        bitmap.setGainmap(gainmap);
                    }
                }
                if (t.rotation != 0) {
                    final Matrix matrix = new Matrix();
                    matrix.setRotate(t.rotation);
                    result = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
                    if (result != bitmap) {
                        DecodeMemory.recycle(bitmap, result);
                    }
                }
                final VmDecode.Decoded decoded = new VmDecode.Decoded(result, t.rotation, t.mirrored, t.sourceError);
                DecodeMemory.keep(result, memory);
                return decoded;
            } catch (RemoteException | VmDecode.RejectedException | VmDecode.UnavailableException
                    | RuntimeException | OutOfMemoryError e) {
                DecodeMemory.recycle(result, null);
                DecodeMemory.recycle(bitmap, null);
                throw e;
            }
        } catch (OutOfMemoryError e) {
            throw VmDecode.UnavailableException.noRoomHere("no memory for reply pixels");
        }
    }

    // The gain map of t: its stripes checked and put into its bitmap a block at a time, its parameters as the
    // contract reads them (DecodeChecks.gainmapOf).
    private static Gainmap gainmap(IDecodeService service, Thumbnail t)
            throws RemoteException, VmDecode.RejectedException, VmDecode.UnavailableException {
        final Bitmap contents = Bitmap.createBitmap(t.gainmapWidth, t.gainmapHeight, Bitmap.Config.ARGB_8888);
        try {
            try (Rows rows = new Rows(contents)) {
            final int stripes = (t.gainmapHeight + t.gainmapStripeRows - 1) / t.gainmapStripeRows;
            for (int i = 0; i < stripes; i++) {
                stillWanted();
                final byte[] stripe = service.readGainmapStripe(t.handle, i);
                final String bad = DecodeChecks.checkGainmap(t, i, stripe);
                if (bad != null) {
                    throw new VmDecode.RejectedException("gain map stripe " + i + " breaks the contract: " + bad);
                }
                rows.put(i * t.gainmapStripeRows, stripe);
            }
            }
            // The parameters as the contract reads them, from a one-pixel copy of the reply's gain map.
            final Thumbnail one = new Thumbnail();
            one.gainmapWidth = 1;
            one.gainmapHeight = 1;
            one.gainmapParams = t.gainmapParams;
            final Gainmap params = DecodeChecks.gainmapOf(one, new byte[] {0, 0, 0, (byte) 0xff});
            if (params == null) {
                contents.recycle();
                return null;
            }
            params.getGainmapContents().recycle();
            return new Gainmap(params, contents);
        } catch (RemoteException | VmDecode.RejectedException | VmDecode.UnavailableException
                | RuntimeException | OutOfMemoryError e) {
            contents.recycle();
            throw e;
        }
    }

    /** Reads every frame stripe of {@code f} (checked as a whole before) into one tall bitmap. */
    static VmDecode.Animation frames(IDecodeService service, Frames f)
            throws RemoteException, VmDecode.RejectedException, VmDecode.UnavailableException {
        final int rows = f.frameCount * f.height;
        final int stripes = (rows + f.stripeRows - 1) / f.stripeRows;
        try (DecodeMemory.Reservation memory = reserve((long) rows * f.width * 4 + 2 * STRIPE_BYTES)) {
            final Bitmap frames = Bitmap.createBitmap(f.width, rows, Bitmap.Config.ARGB_8888, true,
                    android.graphics.GateColorSpaces.of(f.colorSpace, f.colorDescription));
            try {
                try (Rows out = new Rows(frames)) {
                for (int i = 0; i < stripes; i++) {
                    stillWanted();
                    final byte[] stripe = service.readStripe(f.handle, i);
                    final String bad = DecodeChecks.check(f, i, stripe);
                    if (bad != null) {
                        throw new VmDecode.RejectedException("frame stripe " + i + " breaks the contract: " + bad);
                    }
                    out.put(i * f.stripeRows, stripe);
                }
                }
                DecodeMemory.keep(frames, memory);
                return new VmDecode.Animation(frames, f.frameCount, f.width, f.height, f.durations.clone(),
                        f.loopCount);
            } catch (RemoteException | VmDecode.RejectedException | VmDecode.UnavailableException
                    | RuntimeException | OutOfMemoryError e) {
                frames.recycle();
                throw e;
            }
        } catch (OutOfMemoryError e) {
            throw VmDecode.UnavailableException.noRoomHere("no memory for frame pixels");
        }
    }

    /**
     * The turn a provider asks for with a thumbnail (DocumentsContract.EXTRA_ORIENTATION) as a quarter turn of 0, 90,
     * 180 or 270 degrees. Any other angle is refused: it would make the image larger than the box it was decoded for.
     */
    static int quarterTurn(int degrees) throws VmDecode.RejectedException {
        final int turn = ((degrees % 360) + 360) % 360;
        if (turn % 90 != 0) {
            throw new VmDecode.RejectedException("the provider asks for a turn of " + degrees + " degrees");
        }
        return turn;
    }

    // Rows written into a bitmap a stripe at a time: each stripe through a bitmap of its own size, copied as it is
    // (same configuration and colour space, no blending).
    private static final class Rows implements AutoCloseable {
        private final Bitmap mTarget;
        private final Canvas mCanvas;
        private final Paint mCopy = new Paint();
        private Bitmap mStripe;
        private final ByteBuffer mStraight;

        Rows(Bitmap target) {
            this(target, false);
        }

        // Straight pixels, and grey RGBA pixels into an ALPHA_8 target, are copied as bytes.
        Rows(Bitmap target, boolean straight) {
            mTarget = target;
            final boolean grey = target.getConfig() == Bitmap.Config.ALPHA_8;
            if (straight && target.getRowBytes() != target.getWidth()
                    * (grey ? 1 : target.getConfig() == Bitmap.Config.RGBA_F16 ? 8 : 4)) {
                throw new IllegalStateException("straight storage stride");
            }
            mCanvas = straight ? null : new Canvas(target);
            mStraight = straight ? ByteBuffer.allocate(target.getByteCount()) : null;
            mCopy.setBlendMode(BlendMode.SRC);
        }

        void put(int top, byte[] stripe) {
            if (mStraight != null) {
                if ((long) top * mTarget.getRowBytes() != mStraight.position()) {
                    throw new IllegalStateException("inconsistent straight stripe position");
                }
                if (mTarget.getConfig() == Bitmap.Config.ALPHA_8) {
                    for (int i = 0; i < stripe.length; i += 4) mStraight.put(stripe[i]);
                } else {
                    mStraight.put(stripe);
                }
                return;
            }
            final int width = mTarget.getWidth();
            final int n = stripe.length / (width * (mTarget.getConfig() == Bitmap.Config.RGBA_F16 ? 8 : 4));
            if (mStripe == null || mStripe.getHeight() != n) {
                if (mStripe != null) {
                    mStripe.recycle();
                }
                final ColorSpace space = mTarget.getColorSpace();
                mStripe = Bitmap.createBitmap(width, n, mTarget.getConfig(), true,
                        space != null ? space : ColorSpace.get(ColorSpace.Named.SRGB));
            }
            mStripe.copyPixelsFromBuffer(ByteBuffer.wrap(stripe));
            mCanvas.drawBitmap(mStripe, new Rect(0, 0, width, n), new Rect(0, top, width, top + n), mCopy);
        }

        @Override
        public void close() {
            if (mStraight != null && !mStraight.hasRemaining()) {
                mStraight.flip();
                mTarget.copyPixelsFromBuffer(mStraight);
            }
            if (mCanvas != null) mCanvas.setBitmap(null);
            if (mStripe != null) {
                mStripe.recycle();
                mStripe = null;
            }
        }
    }

    // Between a reply's transactions: the caller still waits (its give-up interrupts this call thread).
    private static void stillWanted() throws VmDecode.UnavailableException {
        if (Thread.currentThread().isInterrupted()) {
            throw VmDecode.UnavailableException.callerLeft("the caller left, the rest of the reply is not read");
        }
    }

    private static DecodeMemory.Reservation reserve(long bytes)
            throws VmDecode.UnavailableException, VmDecode.RejectedException {
        try {
            return DecodeMemory.reserve(bytes);
        } catch (DecodeMemory.NoRoomException e) {
            if (e.later) {
                throw VmDecode.UnavailableException.noRoomHere(e.getMessage());
            }
            throw new VmDecode.RejectedException(e.getMessage());
        }
    }
}
