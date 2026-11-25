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

package android.graphics;

import android.annotation.Nullable;
import android.graphics.drawable.Drawable;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * DiamaneOS: what BitmapFactory, ImageDecoder and BitmapRegionDecoder give in a gated process ({@link DecodeGate}),
 * made from what the VM decoded: the sizes, sample sizes, densities, crops, configs, premultiplication, alpha masks,
 * mutability, reused bitmaps, allocators and post-processing of the APIs are applied here to the VM's pixels, by
 * scaling, cropping and drawing, never by decoding. BitmapFactory and BitmapRegionDecoder give the pixels as stored
 * (never turned or mirrored, as their decoders read them), made from the VM's regions of the stored image, so an image
 * above the VM's pixel limit comes in stripes at full detail. ImageDecoder gives them upright (turned and mirrored as
 * the file asks) and its header shows the upright size: the VM's upright decode, or above the VM's limit the stored
 * stripes turned here. Sample sizes are taken as given and sampled sizes and pixels are Android's: the codec's for a
 * whole image (JPEG scaled by its DCT and rounded up, WebP to the nearest size, others down) and a region rounded down,
 * every sample-th pixel of a JPEG's DCT scale or of a PNG, WebP scaled by libwebp. A header is the VM's probe. An
 * opaque image is marked as one (no alpha), so a preferred RGB_565 applies only to it, as in Android. ImageDecoder's
 * drawable of a GIF or WebP animation is animated from the VM's frames ({@link GatedAnimation}), which is not an
 * AnimatedImageDrawable (code that starts only those shows its first frame). Nine-patch chunks are not read: such an
 * image is a plain bitmap. At most {@link #MAX_OUTPUT_PIXELS} are made for one image.
 */
final class GatedDecodes {
    /** A decode makes at most this many pixels, the stored stripes it is made from included. */
    static final long MAX_OUTPUT_PIXELS = 4L * DecodeGate.MAX_VM_PIXELS;
    /** An animation with a post-processor is drawn at its size: all its frames at most this many bytes. */
    static final long MAX_ANIMATION_BYTES = DecodeGate.MAX_BYTES;

    private GatedDecodes() {
    }

    // BitmapFactory

    static Bitmap bitmapFactory(byte[] data, int offset, int length, @Nullable BitmapFactory.Options opts) {
        try (DecodeGate.Op op = DecodeGate.begin();
                DecodeGate.Bytes bytes = GateInput.of(op, data, offset, length)) {
            return retained(bitmapFactory(op, bytes, null, opts), opts);
        } catch (DecodeGate.Failure | OutOfMemoryError e) {
            if (e instanceof OutOfMemoryError) DecodeGate.notNow("no memory for the image");
            return failedBitmap(opts);
        }
    }

    static Bitmap bitmapFactory(InputStream in, @Nullable Rect outPadding, @Nullable BitmapFactory.Options opts) {
        try (DecodeGate.Op op = DecodeGate.begin(); DecodeGate.Bytes bytes = GateInput.of(op, in)) {
            return retained(bitmapFactory(op, bytes, outPadding, opts), opts);
        } catch (DecodeGate.Failure | OutOfMemoryError e) {
            if (e instanceof OutOfMemoryError) DecodeGate.notNow("no memory for the image");
            return failedBitmap(opts);
        }
    }

    static Bitmap bitmapFactory(FileDescriptor fd, @Nullable Rect outPadding,
            @Nullable BitmapFactory.Options opts) {
        try (DecodeGate.Op op = DecodeGate.begin(); DecodeGate.Bytes bytes = GateInput.of(op, fd, -1, -1)) {
            return retained(bitmapFactory(op, bytes, outPadding, opts), opts);
        } catch (DecodeGate.Failure | OutOfMemoryError e) {
            if (e instanceof OutOfMemoryError) DecodeGate.notNow("no memory for the image");
            return failedBitmap(opts);
        }
    }

    static Bitmap bitmapFactory(String path, @Nullable BitmapFactory.Options opts) {
        try (DecodeGate.Op op = DecodeGate.begin();
                GateInput.Acquired input = GateInput.acquireFile(op, new java.io.File(path))) {
            if (input.stream != null) return DecodeGate.trustedBitmap(input.stream.getFD(), opts);
            return retained(bitmapFactory(op, input.bytes, null, opts), opts);
        } catch (DecodeGate.Failure | IOException | RuntimeException | OutOfMemoryError e) {
            if (e instanceof OutOfMemoryError) DecodeGate.notNow("no memory for the image");
            return failedBitmap(opts);
        }
    }

    static Movie movie(String path) {
        try (DecodeGate.Op op = DecodeGate.begin();
                GateInput.Acquired input = GateInput.acquireFile(op, new java.io.File(path))) {
            return input.stream != null ? DecodeGate.trustedMovie(input.stream) : null;
        } catch (DecodeGate.Failure | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private static Bitmap retained(Bitmap bitmap, BitmapFactory.Options options) throws DecodeGate.Failure {
        return options != null && options.inBitmap == bitmap ? bitmap : retained(bitmap);
    }

    private static Bitmap retained(Bitmap bitmap) throws DecodeGate.Failure {
        if (bitmap == null) return null;
        try {
            DecodeMemory.keep(bitmap);
            return bitmap;
        } catch (DecodeMemory.NoRoomException e) {
            DecodeMemory.recycle(bitmap, null);
            throw e.later ? DecodeGate.notNow(e.getMessage()) : DecodeGate.failed(true, e.getMessage());
        } catch (OutOfMemoryError e) {
            DecodeMemory.recycle(bitmap, null);
            throw DecodeGate.notNow("no memory to retain the image");
        }
    }

    private static Bitmap failedBitmap(BitmapFactory.Options opts) {
        if (opts != null) {
            opts.outWidth = -1;
            opts.outHeight = -1;
            opts.outMimeType = null;
            opts.outConfig = null;
            opts.outColorSpace = null;
        }
        return null;
    }

    private static Bitmap bitmapFactory(DecodeGate.Op op, DecodeGate.Bytes bytes, Rect outPadding,
            BitmapFactory.Options opts) throws DecodeGate.Failure {
        final DecodeGate.Info info = DecodeGate.probe(op, bytes, false);
        // As BitmapFactory: the sample size as given, the size its codec samples to, or with the density's scale the
        // size rounded down and scaled.
        final int sample = opts != null && opts.inSampleSize > 1 ? opts.inSampleSize : 1;
        int width = codecSampled(info.mimeType, info.width, sample);
        int height = codecSampled(info.mimeType, info.height, sample);
        if (opts != null && opts.inScaled && opts.inDensity != 0 && opts.inTargetDensity != 0
                && opts.inDensity != opts.inTargetDensity && opts.inDensity != opts.inScreenDensity) {
            final float scale = (float) opts.inTargetDensity / opts.inDensity;
            width = Math.max(1, (int) (info.width / sample * scale + 0.5f));
            height = Math.max(1, (int) (info.height / sample * scale + 0.5f));
        }
        if (opts != null) {
            opts.outWidth = width;
            opts.outHeight = height;
            opts.outMimeType = info.mimeType;
            opts.outConfig = null;
            opts.outColorSpace = null;
            if (opts.inJustDecodeBounds) {
                return null;
            }
        }
        if ((long) width * height > MAX_OUTPUT_PIXELS) {
            throw DecodeGate.failed(true, "larger than the gate makes");
        }
        final int storedSample = storedSample(info, sample);
        final boolean hdrFormat = "image/jpeg".equals(info.mimeType);
        op.checkFit(bytes.reserved() + peakBytes((long) roundedUp(info.width, storedSample) * roundedUp(info.height, storedSample),
                (long) width * height, finalBytesPerPixel(opts)) + (hdrFormat ? GateGainmaps.extraBytes(width, height) : 0));
        Bitmap bitmap = exactly(stored(op, bytes, info.width, info.height, storedSample), width, height);
        // DiamaneOS: regions carry stored base pixels, the whole-image reply supplies the checked gain map.
        try {
            if (hdrFormat) GateGainmaps.attach(op, bytes, bitmap, true);
        } catch (DecodeGate.Failure e) {
            bitmap.recycle();
            throw e;
        }
        markOpaque(bitmap, info.mimeType);
        if (opts != null && !opts.inPremultiplied) {
            bitmap = unpremultiplied(bitmap);
        }
        bitmap = configured(bitmap, opts, bytes);
        if (bitmap == null) {
            return null; // The reused bitmap could not take it: BitmapFactory says so.
        }
        if (outPadding != null) {
            outPadding.set(-1, -1, -1, -1);
        }
        if (opts != null) {
            opts.outConfig = bitmap.getConfig();
            opts.outColorSpace = bitmap.getColorSpace();
        }
        return bitmap;
    }

    // The VM's sample size for the stored pixels of a BitmapFactory decode: the caller's (at most the VM's largest),
    // larger when the stored pixels would be more than the gate makes.
    private static int storedSample(DecodeGate.Info info, int sample) {
        int s = Math.min(sample, DecodeGate.MAX_REGION_SAMPLE);
        while (s < DecodeGate.MAX_REGION_SAMPLE
                && (long) roundedUp(info.width, s) * roundedUp(info.height, s) > MAX_OUTPUT_PIXELS) {
            s++;
        }
        return s;
    }

    private static Bitmap stored(DecodeGate.Op op, DecodeGate.Bytes bytes, int width, int height, int sample)
            throws DecodeGate.Failure {
        return stored(op, bytes, 0, 0, width, height, sample);
    }

    /**
     * A conservative ceiling for a decode, checked before it allocates ({@link DecodeMemory}): {@code stored}
     * pixels as the VM gives them (one answer and its copy, or the whole image and one stripe's answer and copy), the
     * {@code target} pixels in ARGB as it is scaled, cropped or unpremultiplied (the step's source and its result), and
     * the copy handed out at {@code finalBytesPerPixel}. Each step gives back its source before the next one.
     */
    static long peakBytes(long stored, long target, int finalBytesPerPixel) {
        final long vm = Math.min(stored, DecodeGate.MAX_VM_PIXELS) * 4;
        final long storing = stored > DecodeGate.MAX_VM_PIXELS ? stored * 4 + 2 * vm : 2 * vm;
        final long scaling = stored * 4 + target * 4;
        final long finishing = target * 4 + target * Math.max(4, finalBytesPerPixel);
        return Math.max(storing, Math.max(scaling, finishing));
    }

    // Bytes per pixel of what a BitmapFactory call hands out.
    private static int finalBytesPerPixel(BitmapFactory.Options opts) {
        final Bitmap.Config want = opts != null && opts.inPreferredConfig != null ? opts.inPreferredConfig : null;
        return want == Bitmap.Config.RGBA_F16 ? 8 : 4;
    }

    /**
     * The part {@code left}, {@code top}, {@code width} x {@code height} of the image as the file stores it (never
     * turned or mirrored), sampled by {@code sample} as Android's region decoder samples it (the VM's region
     * operation: at most ceil(width / sample) x ceil(height / sample)), from the VM's regions: one, or stripes of at
     * most the VM's pixel limit each. Stripes are of nearly the same height and each a whole number of samples tall
     * but the last, so their sampled rows are the rows of the whole part.
     */
    private static Bitmap stored(DecodeGate.Op op, DecodeGate.Bytes bytes, int left, int top, int width, int height,
            int sample) throws DecodeGate.Failure {
        final int outW = roundedUp(width, sample);
        final int outH = roundedUp(height, sample);
        if ((long) outW * outH > MAX_OUTPUT_PIXELS) {
            throw DecodeGate.failed(true, "larger than the gate makes");
        }
        if ((long) outW * outH <= DecodeGate.MAX_VM_PIXELS) {
            return DecodeGate.region(op, bytes, left, top, width, height, sample);
        }
        final int most = Math.max(1, DecodeGate.MAX_VM_PIXELS / outW); // output rows a stripe may have
        final int stripes = (outH + most - 1) / most;
        Bitmap whole = null;
        Canvas canvas = null;
        final Paint copy = new Paint();
        copy.setBlendMode(BlendMode.SRC);
        int y = 0; // of the part, in stored pixels
        int made = 0; // rows of the output so far
        for (int i = 0; i < stripes && y < height; i++) {
            final int rows = outH / stripes + (i < outH % stripes ? 1 : 0); // of the output, this stripe
            final int tall = Math.min(rows * sample, height - y);
            final Bitmap stripe;
            try {
                stripe = DecodeGate.region(op, bytes, left, top + y, width, tall, sample);
            } catch (DecodeGate.Failure e) {
                if (whole != null) {
                    canvas.setBitmap(null);
                    whole.recycle();
                }
                throw e;
            }
            if (whole == null) {
                whole = GatePixels.blank(stripe.getWidth(), outH, Bitmap.Config.ARGB_8888, true,
                        stripe.getColorSpace());
                canvas = new Canvas(whole);
            }
            if (stripe.getWidth() != whole.getWidth() || made + stripe.getHeight() > outH) {
                stripe.recycle();
                canvas.setBitmap(null);
                whole.recycle();
                throw DecodeGate.failed(false, "bad region stripe");
            }
            canvas.drawBitmap(stripe, 0, made, copy);
            made += stripe.getHeight();
            stripe.recycle();
            y += tall;
        }
        canvas.setBitmap(null);
        if (made < outH) {
            // The sampled height rounds down: the rows past it were never drawn.
            whole.reconfigure(whole.getWidth(), made, Bitmap.Config.ARGB_8888);
        }
        return whole;
    }

    // n / sample rounded up and down (at least 1), and as a codec samples it for ImageDecoder and whole regions.
    static int roundedUp(int n, int sample) {
        return (int) (((long) n + sample - 1) / sample);
    }

    static int roundedDown(int n, int sample) {
        return Math.max(1, n / sample);
    }

    static int codecSampled(String mimeType, int n, int sample) {
        if (sample <= 1) {
            return n;
        }
        if ("image/jpeg".equals(mimeType)) {
            // libjpeg scales by 1/2, 1/4 and 1/8 (rounding up), Skia samples what is left (rounding down).
            for (int native_ : new int[] {8, 4, 2}) {
                if (sample % native_ == 0) {
                    return roundedDown(roundedUp(n, native_), sample / native_);
                }
            }
            return roundedDown(n, sample);
        }
        if ("image/webp".equals(mimeType)) {
            // SkScalingCodec: the scale in float, rounded to the nearest pixel.
            return Math.max(1, (int) Math.floor((1.0f / sample) * n + 0.5f));
        }
        return roundedDown(n, sample);
    }

    // Marks an image without any transparent pixel as opaque, as Android's decoders give an opaque image (a JPEG
    // always is). Read a stripe of rows at a time.
    private static void markOpaque(Bitmap bitmap, String mimeType) throws DecodeGate.Failure {
        if (!bitmap.hasAlpha() || bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            return;
        }
        if (!"image/jpeg".equals(mimeType)) {
            final int w = bitmap.getWidth();
            final int h = bitmap.getHeight();
            final int rows = Math.max(1, Math.min(h, 256 * 1024 / w));
            try (DecodeMemory.Reservation scratch = GatePixels.scratch((long) w * rows * 4)) {
                final int[] colors = new int[w * rows];
                for (int y = 0; y < h; y += rows) {
                    final int n = Math.min(rows, h - y);
                    bitmap.getPixels(colors, 0, w, 0, y, w, n);
                    for (int i = 0; i < w * n; i++) {
                        if ((colors[i] >>> 24) != 0xff) return;
                    }
                }
            }
        }
        bitmap.setHasAlpha(false);
    }

    // The options' config, mutability and reused bitmap applied to a software bitmap of the VM's pixels.
    private static Bitmap configured(Bitmap bitmap, BitmapFactory.Options opts, DecodeGate.Bytes bytes) throws DecodeGate.Failure {
        final Bitmap.Config want = opts != null && opts.inPreferredConfig != null
                ? opts.inPreferredConfig : Bitmap.Config.ARGB_8888;
        final boolean mutable = opts != null && opts.inMutable;
        if (opts != null && opts.inBitmap != null) {
            try {
                return into(bitmap, opts.inBitmap);
            } finally {
                DecodeMemory.recycle(bitmap, opts.inBitmap);
            }
        }
        if (want == Bitmap.Config.HARDWARE) {
            final Bitmap hardware = GatePixels.preferredHardware(bitmap);
            if (hardware == null) return immutable(bitmap);
            DecodeMemory.recycle(bitmap, hardware);
            return hardware;
        }
        if (want == Bitmap.Config.ALPHA_8) {
            // As Android decodes only a one-channel image (grey PNG, grey JPEG) into an alpha mask, others in colour.
            final Bitmap mask = bytes.singleChannel() ? greyAsAlpha(bitmap, mutable) : null;
            if (mask != null) {
                return mask;
            }
        } else if (want != bitmap.getConfig() && want != Bitmap.Config.ARGB_8888
                && (want != Bitmap.Config.RGB_565 || !bitmap.hasAlpha())) {
            // RGB_565 only for an opaque image, as Android keeps the alpha of others in full colour.
            final Bitmap copy = GatePixels.copy(bitmap, want, mutable);
            if (copy != null) {
                DecodeMemory.recycle(bitmap, copy);
                return mutable ? copy : immutable(copy);
            }
        }
        if (mutable && !bitmap.isMutable()) {
            final Bitmap copy = GatePixels.copy(bitmap, bitmap.getConfig(), true);
            DecodeMemory.recycle(bitmap, copy);
            return copy;
        }
        return mutable ? bitmap : immutable(bitmap);
    }

    // Draws {@code bitmap} into the caller's reused bitmap, as BitmapFactory decodes into it, or null when it cannot.
    // As BitmapFactory, clear the previous gain map before installing this image's checked map.
    private static Bitmap into(Bitmap bitmap, Bitmap reused) {
        if (reused.isRecycled()) {
            return null;
        }
        reused.setGainmap(null);
        if (!reused.isMutable() || reused.getConfig() == Bitmap.Config.HARDWARE) {
            return null;
        }
        final Bitmap.Config config = reused.getConfig() != null ? reused.getConfig() : Bitmap.Config.ARGB_8888;
        final int bytesPerPixel = config == Bitmap.Config.RGBA_F16 ? 8
                : config == Bitmap.Config.ALPHA_8 ? 1
                : config == Bitmap.Config.RGB_565 ? 2 : 4;
        if ((long) bitmap.getWidth() * bitmap.getHeight() * bytesPerPixel > reused.getAllocationByteCount()) {
            return null;
        }
        try {
            reused.reconfigure(bitmap.getWidth(), bitmap.getHeight(), config);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return null;
        }
        reused.eraseColor(Color.TRANSPARENT);
        final Canvas canvas = new Canvas(reused);
        canvas.drawBitmap(bitmap, 0, 0, null);
        canvas.setBitmap(null);
        // DiamaneOS: a reused output receives this image's map after its previous map was cleared.
        reused.setGainmap(bitmap.getGainmap());
        return reused;
    }

    // An immutable bitmap of the pixels, as the APIs give by default. A mutable source of the gate's own is given
    // back once copied.
    private static Bitmap immutable(Bitmap bitmap) throws DecodeGate.Failure {
        if (!bitmap.isMutable()) {
            return bitmap;
        }
        final Bitmap copy = GatePixels.copy(bitmap, bitmap.getConfig(), false);
        if (copy == null) {
            return bitmap;
        }
        DecodeMemory.recycle(bitmap, copy);
        return copy;
    }

    // Rows per block of the conversions below: about 256 K pixels at a time, never the whole image in one array.
    private static int blockRows(int width, int height) {
        return Math.max(1, Math.min(height, 256 * 1024 / Math.max(1, width)));
    }

    // The same colours with their alpha not multiplied in, as Android's decoders give them when asked. Converted a
    // block of rows at a time, the source given back after.
    private static Bitmap unpremultiplied(Bitmap bitmap) throws DecodeGate.Failure {
        if (!bitmap.hasAlpha() || bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            return bitmap;
        }
        final int w = bitmap.getWidth();
        final int h = bitmap.getHeight();
        final Bitmap out = GatePixels.blank(w, h, Bitmap.Config.ARGB_8888, true, bitmap.getColorSpace());
        try {
            out.setPremultiplied(false);
            final int rows = blockRows(w, h);
            try (DecodeMemory.Reservation scratch = GatePixels.scratch((long) w * rows * 4)) {
                final int[] colors = new int[w * rows];
                for (int y = 0; y < h; y += rows) {
                    final int n = Math.min(rows, h - y);
                    bitmap.getPixels(colors, 0, w, 0, y, w, n);
                    out.setPixels(colors, 0, w, 0, y, w, n);
                }
            }
            out.setGainmap(bitmap.getGainmap());
            DecodeMemory.recycle(bitmap, out);
            return out;
        } catch (DecodeGate.Failure | RuntimeException | Error e) {
            DecodeMemory.recycle(out, bitmap);
            throw e;
        }
    }

    // A one-channel image's grey as an ALPHA_8 mask (Android's alpha mask), else null. Callers check the encoding.
    // Read and written a block of rows at a time.
    private static Bitmap greyAsAlpha(Bitmap bitmap, boolean mutable) throws DecodeGate.Failure {
        if (bitmap.hasAlpha() || bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            return null;
        }
        final int w = bitmap.getWidth();
        final int h = bitmap.getHeight();
        final int rows = blockRows(w, h);
        try (DecodeMemory.Reservation scratch = GatePixels.scratch((long) w * rows * 5)) {
            final int[] colors = new int[w * rows];
            for (int y = 0; y < h; y += rows) {
                final int n = Math.min(rows, h - y);
                bitmap.getPixels(colors, 0, w, 0, y, w, n);
                for (int i = 0; i < w * n; i++) {
                    final int c = colors[i];
                    final int r = (c >> 16) & 0xff;
                    if (r != ((c >> 8) & 0xff) || r != (c & 0xff) || (c >>> 24) != 0xff) return null;
                }
            }
            final Bitmap mask = GatePixels.blank(w, h, Bitmap.Config.ALPHA_8, true, null);
            Bitmap block = null;
            try {
                block = GatePixels.blank(w, rows, Bitmap.Config.ALPHA_8, true, null);
                final byte[] alpha = new byte[w * rows];
                final Canvas canvas = new Canvas(mask);
                final Paint copy = new Paint();
                copy.setBlendMode(BlendMode.SRC);
                try {
                    for (int y = 0; y < h; y += rows) {
                        final int n = Math.min(rows, h - y);
                        bitmap.getPixels(colors, 0, w, 0, y, w, n);
                        for (int i = 0; i < w * n; i++) alpha[i] = (byte) (colors[i] >> 16);
                        block.copyPixelsFromBuffer(ByteBuffer.wrap(alpha));
                        canvas.drawBitmap(block, new Rect(0, 0, w, n), new Rect(0, y, w, y + n), copy);
                    }
                } finally {
                    canvas.setBitmap(null);
                }
                final Bitmap result = mutable ? mask : immutable(mask);
                DecodeMemory.recycle(bitmap, result);
                return result;
            } catch (DecodeGate.Failure | RuntimeException | Error e) {
                DecodeMemory.recycle(mask, null);
                throw e;
            } finally {
                DecodeMemory.recycle(block, null);
            }
        }
    }

    // {@code bitmap} at exactly {@code width} x {@code height} (the VM fits the box keeping the shape and rounds).
    // The source is given back once scaled.
    private static Bitmap exactly(Bitmap bitmap, int width, int height) throws DecodeGate.Failure {
        if (bitmap.getWidth() == width && bitmap.getHeight() == height) {
            return bitmap;
        }
        final Bitmap scaled = GatePixels.scale(bitmap, Math.max(1, width), Math.max(1, height));
        DecodeMemory.recycle(bitmap, scaled);
        return scaled;
    }

    // ImageDecoder

    /**
     * What an ImageDecoder of the VM holds: the call (its time from the source's open, its memory), the bytes and
     * their header, sizes upright, and the file's orientation.
     */
    static final class Header implements AutoCloseable {
        final DecodeGate.Op op;
        final DecodeGate.Bytes bytes;
        final int width;
        final int height;
        final String mimeType;
        final boolean animated;
        final int storedWidth;
        final int storedHeight;
        final int rotation;
        final boolean mirrored;

        Header(DecodeGate.Op op, DecodeGate.Bytes bytes, DecodeGate.Info info) {
            this.op = op;
            this.bytes = bytes;
            final boolean turned = info.rotation == 90 || info.rotation == 270;
            this.width = turned ? info.height : info.width;
            this.height = turned ? info.width : info.height;
            this.storedWidth = info.width;
            this.storedHeight = info.height;
            this.rotation = info.rotation;
            this.mirrored = info.mirrored;
            this.mimeType = info.mimeType != null ? info.mimeType : "image/unknown";
            // The VM animates GIF and WebP. Other animations (APNG, HEIF, AVIF sequences) are their first frame.
            this.animated = info.animated && ("image/gif".equals(mimeType) || "image/webp".equals(mimeType));
        }

        @Override
        public void close() {
            bytes.close();
            op.close();
        }
    }

    // The reading a header starts with, given the call.
    private interface Input {
        DecodeGate.Bytes read(DecodeGate.Op op) throws DecodeGate.Failure;
    }

    private static Header header(Input input, ImageDecoder.Source source) throws ImageDecoder.DecodeException {
        final DecodeGate.Op op = DecodeGate.begin();
        try {
            return header(op, input.read(op), source);
        } catch (DecodeGate.Failure e) {
            op.close();
            throw decodeException(e, source);
        } catch (OutOfMemoryError e) {
            op.close();
            throw decodeException(DecodeGate.notNow("no memory for the image header"), source);
        } catch (RuntimeException | Error e) {
            op.close();
            throw e;
        }
    }

    static Header header(byte[] data, int offset, int length, ImageDecoder.Source source)
            throws ImageDecoder.DecodeException {
        return header(op -> GateInput.of(op, data, offset, length), source);
    }

    static Header header(ByteBuffer buffer, ImageDecoder.Source source) throws ImageDecoder.DecodeException {
        return header(op -> GateInput.of(op, buffer), source);
    }

    static Header header(InputStream in, ImageDecoder.Source source) throws ImageDecoder.DecodeException {
        return header(op -> GateInput.of(op, in), source);
    }

    static Header header(FileDescriptor fd, long offset, long length, ImageDecoder.Source source)
            throws ImageDecoder.DecodeException {
        return header(op -> GateInput.of(op, fd, offset, length), source);
    }

    /**
     * The header of what {@code uri} opens to, opened as ImageDecoder opens it (typed as an image for content,
     * else plainly, and as a stream when the provider gives no descriptor), the open and the read within the call's
     * time. Null for a verified system file ({@link DecodeGate#exempt}): {@code exempt[0]} is then its open
     * descriptor, decoded as before.
     */
    static Header header(android.content.ContentResolver resolver, android.net.Uri uri, ImageDecoder.Source source,
            android.content.res.AssetFileDescriptor[] exempt) throws IOException {
        final int sourceUser;
        try {
            sourceUser = GateSourceUsers.of(resolver, uri);
        } catch (IllegalArgumentException e) {
            throw new IOException("the image source user is unresolved", e);
        }
        final DecodeGate.Op op = sourceUser >= 0 ? DecodeGate.beginForUri(sourceUser, uri) : DecodeGate.begin();
        try (GateInput.Acquired input = GateInput.acquireUri(op, resolver, uri)) {
            if (input.descriptor != null) {
                exempt[0] = input.descriptor;
                input.descriptor = null;
                op.close();
                return null;
            }
            return header(op, input.takeBytes(), source);
        } catch (DecodeGate.Failure e) {
            op.close();
            throw decodeException(op.received(e), source);
        } catch (OutOfMemoryError e) {
            op.close();
            throw decodeException(DecodeGate.notNow("no memory for the image header"), source);
        } catch (RuntimeException | Error e) {
            op.close();
            throw e;
        }
    }

    /** A file source is acquired and snapshotted on a bounded input worker. */
    static Header header(java.io.File file, ImageDecoder.Source source, java.io.FileInputStream[] exempt)
            throws IOException {
        final DecodeGate.Op op = DecodeGate.begin();
        try (GateInput.Acquired input = GateInput.acquireFile(op, file)) {
            if (input.stream != null) {
                exempt[0] = input.stream;
                input.stream = null;
                op.close();
                return null;
            }
            return header(op, input.takeBytes(), source);
        } catch (DecodeGate.Failure e) {
            op.close();
            throw decodeException(op.received(e), source);
        } catch (OutOfMemoryError e) {
            op.close();
            throw decodeException(DecodeGate.notNow("no memory for the image header"), source);
        } catch (RuntimeException | Error e) {
            op.close();
            throw e;
        }
    }

    static Header header(java.util.concurrent.Callable<android.content.res.AssetFileDescriptor> callable,
            ImageDecoder.Source source) throws IOException {
        return header(op -> {
            if (op.critical) throw DecodeGate.notThisWay("a callable image source needs a worker thread");
            try (android.content.res.AssetFileDescriptor fd = callable.call()) {
                if (fd == null) throw DecodeGate.failed(true, "no image from the callable");
                return GateInput.of(op, fd.getFileDescriptor(), fd.getStartOffset(), fd.getDeclaredLength());
            } catch (DecodeGate.Failure e) {
                throw e;
            } catch (Exception e) {
                throw DecodeGate.notNow("callable input not read: " + e.getClass().getSimpleName());
            }
        }, source);
    }

    // The probe of the bytes read for the call op. On a failure both are closed.
    private static Header header(DecodeGate.Op op, DecodeGate.Bytes bytes, ImageDecoder.Source source)
            throws ImageDecoder.DecodeException {
        try {
            return new Header(op, bytes, DecodeGate.probe(op, bytes, true));
        } catch (DecodeGate.Failure e) {
            bytes.close();
            op.close();
            throw decodeException(e, source);
        } catch (RuntimeException | Error e) {
            bytes.close();
            op.close();
            throw e;
        }
    }

    /** The Size {@code setTargetSampleSize} gives for {@code header}: the codec's sampled size. */
    static android.util.Size sampledSize(Header header, int sampleSize) {
        return new android.util.Size(codecSampled(header.mimeType, header.width, sampleSize),
                codecSampled(header.mimeType, header.height, sampleSize));
    }

    /**
     * The bitmap an ImageDecoder of the VM gives with these settings ({@code width} x {@code height} and
     * {@code crop} upright, as the header gave the size).
     */
    static Bitmap decode(Header header, int width, int height, @Nullable Rect crop, boolean mutable,
            int allocator, boolean unpremultiplied, boolean alphaMask, @Nullable PostProcessor postProcessor,
            ImageDecoder.Source source) throws ImageDecoder.DecodeException {
        try {
            return retained(decodePixels(header, width, height, crop, mutable, allocator, unpremultiplied,
                    alphaMask, postProcessor, source));
        } catch (DecodeGate.Failure e) {
            throw decodeException(e, source);
        } catch (OutOfMemoryError e) {
            throw decodeException(DecodeGate.notNow("no memory for the image"), source);
        }
    }

    private static Bitmap decodePixels(Header header, int width, int height, @Nullable Rect crop, boolean mutable,
            int allocator, boolean unpremultiplied, boolean alphaMask, @Nullable PostProcessor postProcessor,
            ImageDecoder.Source source) throws ImageDecoder.DecodeException {
        try {
            if ((long) width * height > MAX_OUTPUT_PIXELS) {
                throw DecodeGate.failed(true, "larger than the gate makes");
            }
            final long target = (long) width * height;
            final int box = Math.max(width, height);
            header.op.checkFit(header.bytes.reserved() + (target <= DecodeGate.MAX_VM_PIXELS
                    ? peakBytes(Math.min((long) box * box, DecodeGate.MAX_VM_PIXELS), target, 4)
                            + ("image/jpeg".equals(header.mimeType) ? 2 * target * 4 : 0)
                    : peakBytes(largeStoredPixels(header, width, height), target, 4)
                            + ("image/jpeg".equals(header.mimeType) ? GateGainmaps.extraBytes(width, height) : 0)));
            Bitmap bitmap;
            if ((long) width * height <= DecodeGate.MAX_VM_PIXELS) {
                final DecodeGate.Decoded decoded = DecodeGate.decode(header.op, header.bytes, box, box);
                bitmap = exactly(decoded.bitmap, width, height);
            } else {
                bitmap = uprightLarge(header, width, height);
                // DiamaneOS: the region-built base keeps the whole-image reply's upright gain map.
                try {
                    if ("image/jpeg".equals(header.mimeType)) {
                        GateGainmaps.attach(header.op, header.bytes, bitmap, false);
                    }
                } catch (DecodeGate.Failure e) {
                    bitmap.recycle();
                    throw e;
                }
            }
            markOpaque(bitmap, header.mimeType);
            if (unpremultiplied && bitmap.hasAlpha() && !samplesTo(header, width, height)) {
                // As Android: unpremultiplied pixels of an image with alpha are never scaled by a matrix.
                throw new IllegalStateException("Cannot scale unpremultiplied pixels!");
            }
            if (crop != null) {
                final Rect r = new Rect(crop);
                if (!r.intersect(0, 0, bitmap.getWidth(), bitmap.getHeight())) {
                    throw DecodeGate.failed(true, "crop outside the image");
                }
                final Bitmap cropped = GatePixels.crop(bitmap, r.left, r.top, r.width(), r.height());
                DecodeMemory.recycle(bitmap, cropped);
                bitmap = cropped;
            }
            if (postProcessor != null) {
                bitmap = postProcessed(bitmap, postProcessor);
            }
            if (alphaMask && header.bytes.singleChannel()) {
                final Bitmap mask = greyAsAlpha(bitmap, mutable);
                if (mask != null) {
                    return mask;
                }
            }
            if (unpremultiplied) {
                bitmap = unpremultiplied(bitmap);
            }
            if (mutable) {
                if (bitmap.isMutable()) return bitmap;
                final Bitmap copy = GatePixels.copy(bitmap, bitmap.getConfig(), true);
                DecodeMemory.recycle(bitmap, copy);
                return copy;
            }
            if (allocator == ImageDecoder.ALLOCATOR_HARDWARE) {
                final Bitmap hardware = GatePixels.copy(bitmap, Bitmap.Config.HARDWARE, false);
                DecodeMemory.recycle(bitmap, hardware);
                return hardware;
            } else if (allocator == ImageDecoder.ALLOCATOR_DEFAULT && !unpremultiplied) {
                final Bitmap hardware = GatePixels.preferredHardware(bitmap);
                if (hardware != null) {
                    DecodeMemory.recycle(bitmap, hardware);
                    return hardware;
                }
            } else if (allocator == ImageDecoder.ALLOCATOR_SHARED_MEMORY) {
                final Bitmap immutable = immutable(bitmap);
                final Bitmap shared = GatePixels.shared(immutable);
                DecodeMemory.recycle(immutable, shared);
                return shared;
            }
            return immutable(bitmap);
        } catch (DecodeGate.Failure e) {
            throw decodeException(e, source);
        }
    }

    // The VM's sample size for the stored stripes of an image above its pixel limit at width x height upright: as
    // little as the size allows.
    private static int largeSample(Header header, int width, int height) {
        final boolean turned = header.rotation == 90 || header.rotation == 270;
        final int storedW = turned ? height : width;
        final int storedH = turned ? width : height;
        int sample = Math.max(1, Math.min(DecodeGate.MAX_REGION_SAMPLE,
                Math.min(header.storedWidth / storedW, header.storedHeight / storedH)));
        while (sample < DecodeGate.MAX_REGION_SAMPLE && (long) roundedUp(header.storedWidth, sample)
                * roundedUp(header.storedHeight, sample) > MAX_OUTPUT_PIXELS) {
            sample++;
        }
        return sample;
    }

    private static long largeStoredPixels(Header header, int width, int height) {
        final int sample = largeSample(header, width, height);
        return (long) roundedUp(header.storedWidth, sample) * roundedUp(header.storedHeight, sample);
    }

    // An image above the VM's pixel limit at width x height upright: its stored stripes, sampled as little as the size
    // allows, then mirrored and turned as the file asks and scaled in one step.
    private static Bitmap uprightLarge(Header header, int width, int height) throws DecodeGate.Failure {
        final boolean turned = header.rotation == 90 || header.rotation == 270;
        final int sample = largeSample(header, width, height);
        final Bitmap stored = stored(header.op, header.bytes, header.storedWidth, header.storedHeight, sample);
        final Matrix matrix = new Matrix();
        if (header.mirrored) {
            matrix.postScale(-1, 1);
        }
        matrix.postRotate(header.rotation);
        matrix.postScale((float) width / (turned ? stored.getHeight() : stored.getWidth()),
                (float) height / (turned ? stored.getWidth() : stored.getHeight()));
        final Bitmap upright = GatePixels.transform(stored, 0, 0, stored.getWidth(), stored.getHeight(), matrix,
                true);
        DecodeMemory.recycle(stored, upright);
        return exactly(upright, width, height);
    }

    // Draws the post-processor's work on a mutable copy and takes its pixel format, as the decoders do: UNKNOWN keeps
    // the alpha, TRANSLUCENT and OPAQUE set it, anything else is an IllegalArgumentException.
    private static Bitmap postProcessed(Bitmap bitmap, PostProcessor postProcessor) throws DecodeGate.Failure {
        final Bitmap drawn = bitmap.isMutable() && bitmap.getConfig() == Bitmap.Config.ARGB_8888 ? bitmap
                : GatePixels.copy(bitmap, Bitmap.Config.ARGB_8888, true);
        DecodeMemory.recycle(bitmap, drawn);
        final Canvas canvas = new Canvas(drawn);
        final int format = postProcessor.onPostProcess(canvas);
        canvas.setBitmap(null);
        if (format == PixelFormat.OPAQUE) {
            drawn.setHasAlpha(false);
        } else if (format == PixelFormat.TRANSLUCENT) {
            drawn.setHasAlpha(true);
        } else if (format != PixelFormat.UNKNOWN) {
            throw new IllegalArgumentException("invalid return from postProcess: " + format);
        }
        return drawn;
    }

    // Whether the codec samples the stored image to exactly width x height (upright), as SkAndroidCodec's
    // computeSampleSize finds it: else Android scales with a matrix, which it refuses for unpremultiplied pixels.
    private static boolean samplesTo(Header header, int width, int height) {
        final boolean turned = header.rotation == 90 || header.rotation == 270;
        final int w = turned ? height : width;
        final int h = turned ? width : height;
        if ("image/webp".equals(header.mimeType)) {
            // libwebp scales to any smaller size (SkAndroidCodec's supports_any_down_scale), never up.
            return w <= header.storedWidth && h <= header.storedHeight;
        }
        for (int s = 1; s <= Math.max(header.storedWidth, header.storedHeight); s++) {
            final int sw = codecSampled(header.mimeType, header.storedWidth, s);
            final int sh = codecSampled(header.mimeType, header.storedHeight, s);
            if (sw == w && sh == h) {
                return true;
            }
            if (sw < w || sh < h) {
                return false;
            }
        }
        return false;
    }

    /**
     * The animated drawable an ImageDecoder of the VM gives for a GIF or WebP animation: its frames decoded in the VM
     * to fit {@code width} x {@code height} (all of them at most the service's frame byte limit), each a bitmap of its
     * own, shown at that size (scaled when drawn, never made larger here). With a post-processor every frame is made at
     * that size and drawn on, at most {@link #MAX_ANIMATION_BYTES} for all of them.
     */
    static Drawable animated(Header header, int width, int height, @Nullable Rect crop,
            @Nullable PostProcessor postProcessor, ImageDecoder.Source source)
            throws ImageDecoder.DecodeException {
        try {
            // The frames come at most as the service's frame limit (MAX_ANIMATION_BYTES): the answer, its copy and
            // the frames cut from it, and with a post-processor the frames drawn at their size.
            header.op.checkFit(header.bytes.reserved() + 3 * MAX_ANIMATION_BYTES
                    + (postProcessor != null ? MAX_ANIMATION_BYTES : 0));
            final DecodeGate.Frames f = DecodeGate.frames(header.op, header.bytes, width, height);
            Rect r = null;
            if (crop != null) {
                r = new Rect(crop);
                if (!r.intersect(0, 0, width, height)) {
                    throw DecodeGate.failed(true, "crop outside the image");
                }
            }
            final boolean drawn = postProcessor != null;
            if (drawn && (long) f.count * width * height * 4 > MAX_ANIMATION_BYTES) {
                throw DecodeGate.failed(true, "animation larger than the gate makes");
            }
            final Bitmap[] frames = new Bitmap[f.count];
            for (int i = 0; i < f.count; i++) {
                Bitmap frame = GatePixels.crop(f.frames, 0, i * f.height, f.width, f.height);
                if (drawn) {
                    frame = exactly(frame, width, height);
                    final Bitmap sized = frame.isMutable() ? frame
                            : GatePixels.copy(frame, Bitmap.Config.ARGB_8888, true);
                    DecodeMemory.recycle(frame, sized);
                    final Canvas canvas = new Canvas(sized);
                    postProcessor.onPostProcess(canvas);
                    canvas.setBitmap(null);
                    frame = sized;
                }
                frames[i] = immutable(frame);
            }
            if (frames[0] != f.frames) {
                f.frames.recycle();
            }
            return new GatedAnimation(frames, width, height, r, f.durations, f.loops);
        } catch (DecodeGate.Failure e) {
            throw decodeException(e, source);
        } catch (OutOfMemoryError e) {
            throw decodeException(DecodeGate.failed(false, "no memory for the frames"), source);
        }
    }

    private static ImageDecoder.DecodeException decodeException(DecodeGate.Failure e,
            ImageDecoder.Source source) {
        return new ImageDecoder.DecodeException(e.refused ? ImageDecoder.DecodeException.SOURCE_MALFORMED_DATA
                : ImageDecoder.DecodeException.SOURCE_EXCEPTION,
                "not decoded in the protected VM: " + e.getMessage(), null, source);
    }

    // BitmapRegionDecoder

    /** The formats Android's region decoder takes (skia::BitmapRegionDecoder::Make). */
    private static final java.util.Set<String> REGION_TYPES = java.util.Set.of("image/jpeg", "image/png",
            "image/webp", "image/heif", "image/avif");

    static Region region(byte[] data, int offset, int length) throws IOException {
        return region(op -> GateInput.of(op, data, offset, length));
    }

    static Region region(InputStream in) throws IOException {
        return region(op -> GateInput.of(op, in));
    }

    static Region region(FileDescriptor fd) throws IOException {
        return region(op -> GateInput.of(op, fd, -1, -1));
    }

    // A region decoder of the bytes the call reads. The call ends with the probe, the bytes stay with the decoder.
    private static Region region(Input input) throws IOException {
        try (DecodeGate.Op op = DecodeGate.begin()) {
            return region(op, input.read(op));
        } catch (DecodeGate.Failure e) {
            throw new IOException("Image format not supported: " + e.getMessage());
        }
    }

    static Region region(String path) throws IOException {
        try (DecodeGate.Op op = DecodeGate.begin();
                GateInput.Acquired input = GateInput.acquireFile(op, new java.io.File(path), true)) {
            return region(op, input.takeBytes());
        } catch (DecodeGate.Failure e) {
            throw new IOException("Image input unavailable", e);
        } catch (OutOfMemoryError e) {
            throw new IOException("Image allocation unavailable", e);
        }
    }

    private static Region region(DecodeGate.Op op, DecodeGate.Bytes bytes) throws IOException {
        try {
            final DecodeGate.Info info = DecodeGate.probe(op, bytes, false);
            if (!REGION_TYPES.contains(info.mimeType)) {
                // As Android's region decoder: these formats only.
                throw DecodeGate.failed(true, info.mimeType + " has no regions");
            }
            return new Region(bytes, info.width, info.height, info.mimeType, op.owner);
        } catch (OutOfMemoryError e) {
            bytes.close();
            throw new IOException("Image allocation unavailable", e);
        } catch (DecodeGate.Failure e) {
            bytes.close();
            throw new IOException("Image format not supported: " + e.getMessage());
        }
    }

    /**
     * A region decoder of the VM: each region is decoded in the VM at full detail (the VM's region operation reads only
     * the region's rows and, for JPEG, its columns), scaled by the sample size, never turned. Answers are kept by the
     * gate's cache by rect and sample size, so a cropper panning back over a tile has it at once. Sizes, sampled
     * pixels and rects past the image's edges are Android's: a rect that starts above or left of the image gives a
     * bitmap of the rect's size with the part outside transparent, one that starts in the image only its part in it.
     */
    static final class Region {
        private final DecodeGate.Bytes mBytes;
        final int width;
        final int height;
        private final String mMimeType;
        private final com.android.internal.diamaneos.cleancopies.GateOwner mSourceUser;
        private boolean mRecycled;

        Region(DecodeGate.Bytes bytes, int width, int height, String mimeType,
                com.android.internal.diamaneos.cleancopies.GateOwner sourceUser) {
            mSourceUser = sourceUser;
            mBytes = bytes;
            this.width = width;
            this.height = height;
            mMimeType = mimeType;
        }

        synchronized Bitmap decodeRegion(Rect rect, @Nullable BitmapFactory.Options opts) {
            try (DecodeGate.Op op = DecodeGate.beginForSource(mSourceUser)) {
                if (mRecycled) {
                    throw DecodeGate.failed(false, "recycled");
                }
                // As Android's region decoder (skia::BitmapRegionDecoder): the sample size as given.
                final int sample = opts != null && opts.inSampleSize > 1 ? opts.inSampleSize : 1;
                final Rect r = new Rect(rect);
                if (!r.intersect(0, 0, width, height)) {
                    throw DecodeGate.failed(true, "region outside the image");
                }
                // A rect that starts above or left of the image is placed where it starts, the rest of it
                // transparent (adjust_subset_rect: "partially inside"). One that starts in the image gives only its
                // part in the image.
                final int skipX = r.left - rect.left;
                final int skipY = r.top - rect.top;
                final boolean partly = skipX != 0 || skipY != 0;
                if ("image/webp".equals(mMimeType)) {
                    // libwebp cuts from an even corner: Skia moves an odd one up and left, so the part is a pixel
                    // larger there.
                    r.left &= ~1;
                    r.top &= ~1;
                }
                // The part sampled: the whole image as its codec samples it, a part rounded down.
                final boolean whole = r.width() == width && r.height() == height;
                final int partW = whole ? codecSampled(mMimeType, width, sample) : roundedDown(r.width(), sample);
                final int partH = whole ? codecSampled(mMimeType, height, sample) : roundedDown(r.height(), sample);
                final int outX = partly ? skipX / sample : 0;
                final int outY = partly ? skipY / sample : 0;
                final int outWidth = !partly ? partW
                        : outX + partW + Math.max(0, rect.width() - skipX - r.width()) / sample;
                final int outHeight = !partly ? partH
                        : outY + partH + Math.max(0, rect.height() - skipY - r.height()) / sample;
                if ((long) outWidth * outHeight > MAX_OUTPUT_PIXELS) {
                    throw DecodeGate.failed(true, "larger than the gate makes");
                }
                int vmSample = Math.min(sample, DecodeGate.MAX_REGION_SAMPLE);
                while (vmSample < DecodeGate.MAX_REGION_SAMPLE && (long) roundedUp(r.width(), vmSample)
                        * roundedUp(r.height(), vmSample) > MAX_OUTPUT_PIXELS) {
                    vmSample++;
                }
                op.checkFit(mBytes.reserved() + peakBytes((long) roundedUp(r.width(), vmSample) * roundedUp(r.height(), vmSample),
                        (long) outWidth * outHeight, finalBytesPerPixel(opts)));
                Bitmap bitmap = exactly(stored(op, mBytes, r.left, r.top, r.width(), r.height(), vmSample), partW,
                        partH);
                if (partly) {
                    // The rect starts outside the image: its part in the image placed where it is, the rest
                    // transparent.
                    final Bitmap all = GatePixels.blank(outWidth, outHeight, Bitmap.Config.ARGB_8888, true,
                            bitmap.getColorSpace());
                    final Canvas canvas = new Canvas(all);
                    canvas.drawBitmap(bitmap, outX, outY, null);
                    canvas.setBitmap(null);
                    bitmap.recycle();
                    bitmap = all;
                }
                markOpaque(bitmap, mMimeType);
                if (opts != null && !opts.inPremultiplied) {
                    bitmap = unpremultiplied(bitmap);
                }
                bitmap = configured(bitmap, opts, mBytes);
                if (opts != null) {
                    opts.outWidth = outWidth;
                    opts.outHeight = outHeight;
                    opts.outMimeType = mMimeType;
                    if (bitmap != null) {
                        opts.outConfig = bitmap.getConfig();
                        opts.outColorSpace = bitmap.getColorSpace();
                    }
                }
                return bitmap;
            } catch (DecodeGate.Failure e) {
                return null;
            }
        }

        synchronized void recycle() {
            mRecycled = true;
            mBytes.close(); // once, however often
        }
    }
}
