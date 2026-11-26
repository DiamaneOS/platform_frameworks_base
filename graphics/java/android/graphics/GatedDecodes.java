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
        try (DecodeGate.Bytes bytes = DecodeGate.Bytes.of(data, offset, length)) {
            return bitmapFactory(bytes, null, opts);
        } catch (DecodeGate.Failure e) {
            return failedBitmap(opts);
        }
    }

    static Bitmap bitmapFactory(InputStream in, @Nullable Rect outPadding, @Nullable BitmapFactory.Options opts) {
        try (DecodeGate.Bytes bytes = DecodeGate.Bytes.of(in)) {
            return bitmapFactory(bytes, outPadding, opts);
        } catch (DecodeGate.Failure e) {
            return failedBitmap(opts);
        }
    }

    static Bitmap bitmapFactory(FileDescriptor fd, @Nullable Rect outPadding,
            @Nullable BitmapFactory.Options opts) {
        try (DecodeGate.Bytes bytes = DecodeGate.Bytes.of(fd, -1, -1)) {
            return bitmapFactory(bytes, outPadding, opts);
        } catch (DecodeGate.Failure e) {
            return failedBitmap(opts);
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

    private static Bitmap bitmapFactory(DecodeGate.Bytes bytes, Rect outPadding, BitmapFactory.Options opts)
            throws DecodeGate.Failure {
        final DecodeGate.Info info = DecodeGate.probe(bytes, false);
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
        Bitmap bitmap = exactly(stored(bytes, info.width, info.height, storedSample(info, sample)), width, height);
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

    private static Bitmap stored(DecodeGate.Bytes bytes, int width, int height, int sample) throws DecodeGate.Failure {
        return stored(bytes, 0, 0, width, height, sample);
    }

    /**
     * The part {@code left}, {@code top}, {@code width} x {@code height} of the image as the file stores it (never
     * turned or mirrored), sampled by {@code sample} as Android's region decoder samples it (the VM's region
     * operation: at most ceil(width / sample) x ceil(height / sample)), from the VM's regions: one, or stripes of at
     * most the VM's pixel limit each. Stripes are of nearly the same height and each a whole number of samples tall
     * but the last, so their sampled rows are the rows of the whole part.
     */
    private static Bitmap stored(DecodeGate.Bytes bytes, int left, int top, int width, int height, int sample)
            throws DecodeGate.Failure {
        final int outW = roundedUp(width, sample);
        final int outH = roundedUp(height, sample);
        if ((long) outW * outH > MAX_OUTPUT_PIXELS) {
            throw DecodeGate.failed(true, "larger than the gate makes");
        }
        if ((long) outW * outH <= DecodeGate.MAX_VM_PIXELS) {
            return DecodeGate.region(bytes, left, top, width, height, sample);
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
            final Bitmap stripe = DecodeGate.region(bytes, left, top + y, width, tall, sample);
            if (whole == null) {
                whole = Bitmap.createBitmap(stripe.getWidth(), outH, Bitmap.Config.ARGB_8888, true,
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
    private static void markOpaque(Bitmap bitmap, String mimeType) {
        if (!bitmap.hasAlpha() || bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            return;
        }
        if (!"image/jpeg".equals(mimeType)) {
            final int w = bitmap.getWidth();
            final int h = bitmap.getHeight();
            final int rows = Math.max(1, Math.min(h, 256 * 1024 / w));
            final int[] colors = new int[w * rows];
            for (int y = 0; y < h; y += rows) {
                final int n = Math.min(rows, h - y);
                bitmap.getPixels(colors, 0, w, 0, y, w, n);
                for (int i = 0; i < w * n; i++) {
                    if ((colors[i] >>> 24) != 0xff) {
                        return;
                    }
                }
            }
        }
        bitmap.setHasAlpha(false);
    }

    // The options' config, mutability and reused bitmap applied to a software bitmap of the VM's pixels.
    private static Bitmap configured(Bitmap bitmap, BitmapFactory.Options opts, DecodeGate.Bytes bytes) {
        final Bitmap.Config want = opts != null && opts.inPreferredConfig != null
                ? opts.inPreferredConfig : Bitmap.Config.ARGB_8888;
        final boolean mutable = opts != null && opts.inMutable;
        if (opts != null && opts.inBitmap != null) {
            return into(bitmap, opts.inBitmap);
        }
        if (want == Bitmap.Config.HARDWARE) {
            final Bitmap hardware = bitmap.copy(Bitmap.Config.HARDWARE, false);
            return hardware != null ? hardware : immutable(bitmap);
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
            final Bitmap copy = bitmap.copy(want, mutable);
            if (copy != null) {
                return mutable ? copy : immutable(copy);
            }
        }
        if (mutable && !bitmap.isMutable()) {
            return bitmap.copy(bitmap.getConfig(), true);
        }
        return mutable ? bitmap : immutable(bitmap);
    }

    // Draws {@code bitmap} into the caller's reused bitmap, as BitmapFactory decodes into it, or null when it cannot.
    private static Bitmap into(Bitmap bitmap, Bitmap reused) {
        if (reused.isRecycled() || !reused.isMutable() || reused.getConfig() == Bitmap.Config.HARDWARE) {
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
        return reused;
    }

    // An immutable bitmap of the pixels, as the APIs give by default.
    private static Bitmap immutable(Bitmap bitmap) {
        if (!bitmap.isMutable()) {
            return bitmap;
        }
        final Bitmap copy = bitmap.copy(bitmap.getConfig(), false);
        return copy != null ? copy : bitmap;
    }

    // The same colours with their alpha not multiplied in, as Android's decoders give them when asked.
    private static Bitmap unpremultiplied(Bitmap bitmap) {
        if (!bitmap.hasAlpha() || bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            return bitmap;
        }
        final int w = bitmap.getWidth();
        final int h = bitmap.getHeight();
        final int[] colors = new int[w * h];
        bitmap.getPixels(colors, 0, w, 0, 0, w, h); // getPixels gives unpremultiplied colours
        final Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888, true, bitmap.getColorSpace());
        out.setPremultiplied(false);
        out.setPixels(colors, 0, w, 0, 0, w, h);
        return out;
    }

    // A one-channel image's grey as an ALPHA_8 mask (Android's alpha mask), else null. Callers check the encoding.
    private static Bitmap greyAsAlpha(Bitmap bitmap, boolean mutable) {
        if (bitmap.hasAlpha() || bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            return null;
        }
        final int w = bitmap.getWidth();
        final int h = bitmap.getHeight();
        final int[] colors = new int[w * h];
        bitmap.getPixels(colors, 0, w, 0, 0, w, h);
        final byte[] alpha = new byte[w * h];
        for (int i = 0; i < colors.length; i++) {
            final int c = colors[i];
            final int r = (c >> 16) & 0xff;
            if (r != ((c >> 8) & 0xff) || r != (c & 0xff) || (c >>> 24) != 0xff) {
                return null; // Colour or transparency: not a one-channel image.
            }
            alpha[i] = (byte) r;
        }
        final Bitmap mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        mask.copyPixelsFromBuffer(ByteBuffer.wrap(alpha));
        return mutable ? mask : immutable(mask);
    }

    // {@code bitmap} at exactly {@code width} x {@code height} (the VM fits the box keeping the shape and rounds).
    private static Bitmap exactly(Bitmap bitmap, int width, int height) {
        if (bitmap.getWidth() == width && bitmap.getHeight() == height) {
            return bitmap;
        }
        return Bitmap.createScaledBitmap(bitmap, Math.max(1, width), Math.max(1, height), true);
    }

    // ImageDecoder

    /** What an ImageDecoder of the VM holds: the bytes and their header, sizes upright, and the file's orientation. */
    static final class Header implements AutoCloseable {
        final DecodeGate.Bytes bytes;
        final int width;
        final int height;
        final String mimeType;
        final boolean animated;
        final int storedWidth;
        final int storedHeight;
        final int rotation;
        final boolean mirrored;

        Header(DecodeGate.Bytes bytes, DecodeGate.Info info) {
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
        }
    }

    static Header header(byte[] data, int offset, int length, ImageDecoder.Source source)
            throws ImageDecoder.DecodeException {
        try {
            return header(DecodeGate.Bytes.of(data, offset, length), source);
        } catch (DecodeGate.Failure e) {
            throw decodeException(e, source);
        }
    }

    static Header header(ByteBuffer buffer, ImageDecoder.Source source) throws ImageDecoder.DecodeException {
        try {
            return header(DecodeGate.Bytes.of(buffer), source);
        } catch (DecodeGate.Failure e) {
            throw decodeException(e, source);
        }
    }

    static Header header(InputStream in, ImageDecoder.Source source) throws ImageDecoder.DecodeException {
        try {
            return header(DecodeGate.Bytes.of(in), source);
        } catch (DecodeGate.Failure e) {
            throw decodeException(e, source);
        }
    }

    static Header header(FileDescriptor fd, long offset, long length, ImageDecoder.Source source)
            throws ImageDecoder.DecodeException {
        try {
            return header(DecodeGate.Bytes.of(fd, offset, length), source);
        } catch (DecodeGate.Failure e) {
            throw decodeException(e, source);
        }
    }

    private static Header header(DecodeGate.Bytes bytes, ImageDecoder.Source source)
            throws ImageDecoder.DecodeException {
        try {
            return new Header(bytes, DecodeGate.probe(bytes, true));
        } catch (DecodeGate.Failure e) {
            bytes.close();
            throw decodeException(e, source);
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
            if ((long) width * height > MAX_OUTPUT_PIXELS) {
                throw DecodeGate.failed(true, "larger than the gate makes");
            }
            Bitmap bitmap;
            if ((long) width * height <= DecodeGate.MAX_VM_PIXELS) {
                final DecodeGate.Decoded decoded = DecodeGate.decode(header.bytes, Math.max(width, height),
                        Math.max(width, height));
                bitmap = exactly(decoded.bitmap, width, height);
            } else {
                bitmap = uprightLarge(header, width, height);
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
                bitmap = Bitmap.createBitmap(bitmap, r.left, r.top, r.width(), r.height());
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
                return bitmap.isMutable() ? bitmap : bitmap.copy(bitmap.getConfig(), true);
            }
            if (allocator == ImageDecoder.ALLOCATOR_HARDWARE
                    || (allocator == ImageDecoder.ALLOCATOR_DEFAULT && !unpremultiplied)) {
                final Bitmap hardware = bitmap.copy(Bitmap.Config.HARDWARE, false);
                if (hardware != null) {
                    return hardware;
                }
            } else if (allocator == ImageDecoder.ALLOCATOR_SHARED_MEMORY) {
                return immutable(bitmap).asShared();
            }
            return immutable(bitmap);
        } catch (DecodeGate.Failure e) {
            throw decodeException(e, source);
        }
    }

    // An image above the VM's pixel limit at width x height upright: its stored stripes, sampled as little as the size
    // allows, then mirrored and turned as the file asks and scaled in one step.
    private static Bitmap uprightLarge(Header header, int width, int height) throws DecodeGate.Failure {
        final boolean turned = header.rotation == 90 || header.rotation == 270;
        final int storedW = turned ? height : width;
        final int storedH = turned ? width : height;
        int sample = Math.max(1, Math.min(DecodeGate.MAX_REGION_SAMPLE,
                Math.min(header.storedWidth / storedW, header.storedHeight / storedH)));
        while (sample < DecodeGate.MAX_REGION_SAMPLE && (long) roundedUp(header.storedWidth, sample)
                * roundedUp(header.storedHeight, sample) > MAX_OUTPUT_PIXELS) {
            sample++;
        }
        final Bitmap stored = stored(header.bytes, header.storedWidth, header.storedHeight, sample);
        final Matrix matrix = new Matrix();
        if (header.mirrored) {
            matrix.postScale(-1, 1);
        }
        matrix.postRotate(header.rotation);
        matrix.postScale((float) width / (turned ? stored.getHeight() : stored.getWidth()),
                (float) height / (turned ? stored.getWidth() : stored.getHeight()));
        final Bitmap upright = Bitmap.createBitmap(stored, 0, 0, stored.getWidth(), stored.getHeight(), matrix,
                true);
        if (upright != stored) {
            stored.recycle();
        }
        return exactly(upright, width, height);
    }

    // Draws the post-processor's work on a mutable copy and takes its pixel format, as the decoders do: UNKNOWN keeps
    // the alpha, TRANSLUCENT and OPAQUE set it, anything else is an IllegalArgumentException.
    private static Bitmap postProcessed(Bitmap bitmap, PostProcessor postProcessor) {
        final Bitmap drawn = bitmap.isMutable() && bitmap.getConfig() == Bitmap.Config.ARGB_8888 ? bitmap
                : bitmap.copy(Bitmap.Config.ARGB_8888, true);
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
            final DecodeGate.Frames f = DecodeGate.frames(header.bytes, width, height);
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
                Bitmap frame = Bitmap.createBitmap(f.frames, 0, i * f.height, f.width, f.height);
                if (drawn) {
                    final Bitmap sized = frame.getWidth() == width && frame.getHeight() == height
                            && frame.isMutable() ? frame
                            : Bitmap.createScaledBitmap(frame, width, height, true).copy(Bitmap.Config.ARGB_8888,
                                    true);
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
        try {
            return region(DecodeGate.Bytes.of(data, offset, length));
        } catch (DecodeGate.Failure e) {
            throw new IOException("Image format not supported: " + e.getMessage());
        }
    }

    static Region region(InputStream in) throws IOException {
        try {
            return region(DecodeGate.Bytes.of(in));
        } catch (DecodeGate.Failure e) {
            throw new IOException("Image format not supported: " + e.getMessage());
        }
    }

    static Region region(FileDescriptor fd) throws IOException {
        try {
            return region(DecodeGate.Bytes.of(fd, -1, -1));
        } catch (DecodeGate.Failure e) {
            throw new IOException("Image format not supported: " + e.getMessage());
        }
    }

    private static Region region(DecodeGate.Bytes bytes) throws IOException {
        try {
            final DecodeGate.Info info = DecodeGate.probe(bytes, false);
            if (!REGION_TYPES.contains(info.mimeType)) {
                // As Android's region decoder: these formats only.
                throw DecodeGate.failed(true, info.mimeType + " has no regions");
            }
            return new Region(bytes, info.width, info.height, info.mimeType);
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
        private boolean mRecycled;

        Region(DecodeGate.Bytes bytes, int width, int height, String mimeType) {
            mBytes = bytes;
            this.width = width;
            this.height = height;
            mMimeType = mimeType;
        }

        synchronized Bitmap decodeRegion(Rect rect, @Nullable BitmapFactory.Options opts) {
            try {
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
                Bitmap bitmap = exactly(stored(mBytes, r.left, r.top, r.width(), r.height(), vmSample), partW,
                        partH);
                if (partly) {
                    // The rect starts outside the image: its part in the image placed where it is, the rest
                    // transparent.
                    final Bitmap all = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888, true,
                            bitmap.getColorSpace());
                    final Canvas canvas = new Canvas(all);
                    canvas.drawBitmap(bitmap, outX, outY, null);
                    canvas.setBitmap(null);
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
            mBytes.close();
        }
    }
}
