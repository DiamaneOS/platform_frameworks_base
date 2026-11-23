/* Copyright (C) 2026 DiamaneOS */
package android.graphics;

import android.util.Half;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Straight raw pixels retain transparent colour and wide precision through discrete orientation. */
final class GateStraightPixels {
    static Bitmap decode(GatedDecodes.Header header, int width, int height, int sample, Rect crop,
            ColorSpace target, boolean mutable, int allocator, boolean conserveMemory) throws DecodeGate.Failure {
        final boolean sourceWide = header.bitDepth > 8;
        final boolean wide = !conserveMemory && (sourceWide || target != null
                && (target.equals(ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB))
                    || target.equals(ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB))));
        final int sourceBytes = sourceWide ? 8 : 4;
        final int bytes = wide ? 8 : 4;
        final boolean turned = header.rotation == 90 || header.rotation == 270;
        final int storedWidth = turned ? height : width;
        final int storedHeight = turned ? width : height;
        final Rect bounds = crop == null ? new Rect(0, 0, width, height) : new Rect(crop);
        final long image = (long) bounds.width() * bounds.height() * bytes;
        header.op.checkFit(header.bytes.reserved() + 2 * image + 4L * 32 * 1024 * 1024 + 2 * 1024 * 1024);
        if (sample < 1 || sample > DecodeGate.MAX_REGION_SAMPLE || image <= 0 || image > Integer.MAX_VALUE) {
            throw DecodeGate.failed(true, "straight image dimensions");
        }
        Bitmap result = null;
        Bitmap tile = null;
        boolean complete = false;
        try (DecodeMemory.Reservation scratch = GatePixels.scratch(image)) {
            final ByteBuffer pixels = ByteBuffer.allocate((int) image).order(ByteOrder.LITTLE_ENDIAN);
            ColorSpace source = null;
            final boolean webp = "image/webp".equals(header.mimeType);
            final int columns = webp ? storedWidth : Math.min(storedWidth, 262144 / sourceBytes);
            final int rows = webp ? storedHeight
                    : Math.max(1, (DecodeGate.MAX_VM_PIXELS / (sourceBytes / 4)) / columns);
            for (int top = 0; top < storedHeight; top += rows) {
                final int tall = Math.min(rows, storedHeight - top);
                for (int left = 0; left < storedWidth; left += columns) {
                    final int widePixels = Math.min(columns, storedWidth - left);
                    final int sourceLeft = left * sample;
                    final int sourceTop = top * sample;
                    final int sourceRight = Math.min(header.storedWidth, (left + widePixels) * sample);
                    final int sourceBottom = Math.min(header.storedHeight, (top + tall) * sample);
                    tile = webp ? DecodeGate.straightWebp(header.op, header.bytes, storedWidth, storedHeight)
                            : DecodeGate.region(header.op, header.bytes, sourceLeft, sourceTop,
                                    sourceRight - sourceLeft, sourceBottom - sourceTop, sample, sourceWide ? 3 : 2);
                    if (tile.getWidth() != widePixels || tile.getHeight() != tall
                            || tile.getRowBytes() != widePixels * sourceBytes
                            || (sourceWide ? tile.getConfig() != Bitmap.Config.RGBA_F16
                                     : tile.getConfig() != Bitmap.Config.ARGB_8888)
                            || tile.hasAlpha() && tile.isPremultiplied()) {
                        throw DecodeGate.failed(false, "straight region shape");
                    }
                    if (source == null) source = tile.getColorSpace();
                    else if (!java.util.Objects.equals(source, tile.getColorSpace())) {
                        throw DecodeGate.failed(false, "straight region colour");
                    }
                    final ColorSpace destination = outputColor(source, target, wide);
                    final ColorSpace.Connector connector = sameEncoding(source, destination) ? null
                            : ColorSpace.connect(source, destination);
                    final float[] rgb = new float[3];
                    try (DecodeMemory.Reservation tileScratch = GatePixels.scratch(tile.getByteCount())) {
                        final ByteBuffer raw = ByteBuffer.allocate(tile.getByteCount()).order(ByteOrder.LITTLE_ENDIAN);
                        tile.copyPixelsToBuffer(raw);
                        for (int y = 0; y < tall; y++) {
                            for (int x = 0; x < widePixels; x++) {
                                final int sx = header.mirrored ? storedWidth - 1 - left - x : left + x;
                                final int sy = top + y;
                                final int dx = header.rotation == 90 ? storedHeight - 1 - sy
                                        : header.rotation == 180 ? storedWidth - 1 - sx
                                        : header.rotation == 270 ? sy : sx;
                                final int dy = header.rotation == 90 ? sx
                                        : header.rotation == 180 ? storedHeight - 1 - sy
                                        : header.rotation == 270 ? storedWidth - 1 - sx : sy;
                                if (!bounds.contains(dx, dy)) continue;
                                final int from = (y * widePixels + x) * sourceBytes;
                                final int to = ((dy - bounds.top) * bounds.width() + dx - bounds.left) * bytes;
                                copyPixel(pixels, to, bytes, raw, from, sourceBytes, connector, rgb);
                            }
                        }
                    }
                    DecodeMemory.recycle(tile, null);
                    tile = null;
                }
            }
            final ColorSpace destination = outputColor(source, target, wide);
            if (source == null || destination == null) throw DecodeGate.failed(false, "straight colour missing");
            result = GatePixels.blank(bounds.width(), bounds.height(),
                    wide ? Bitmap.Config.RGBA_F16 : Bitmap.Config.ARGB_8888, true, destination);
            if (result.getRowBytes() != bounds.width() * bytes) {
                throw DecodeGate.failed(false, "straight storage stride");
            }
            result.setPremultiplied(false);
            pixels.position(0);
            result.copyPixelsFromBuffer(pixels);
            if (!mutable) result.freezeForDecodeGate();
            if (allocator == ImageDecoder.ALLOCATOR_HARDWARE) {
                final Bitmap hardware = GatePixels.copy(result, Bitmap.Config.HARDWARE, false);
                if (hardware.hasAlpha() && hardware.isPremultiplied()) {
                    DecodeMemory.recycle(hardware, null);
                    throw DecodeGate.failed(false, "straight hardware alpha");
                }
                DecodeMemory.recycle(result, hardware);
                result = hardware;
            }
            complete = true;
            return result;
        } finally {
            DecodeMemory.recycle(tile, null);
            if (!complete) DecodeMemory.recycle(result, null);
        }
    }

    private static ColorSpace outputColor(ColorSpace source, ColorSpace target, boolean wide) {
        if (target != null) return target;
        if (!wide && ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB).equals(source)) {
            return ColorSpace.get(ColorSpace.Named.SRGB);
        }
        if (!wide && ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB).equals(source)) {
            return ColorSpace.get(ColorSpace.Named.LINEAR_SRGB);
        }
        return source;
    }

    private static boolean sameEncoding(ColorSpace source, ColorSpace destination) {
        if (source.equals(destination)) return true;
        final ColorSpace srgb = ColorSpace.get(ColorSpace.Named.SRGB);
        final ColorSpace extended = ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB);
        if ((source.equals(srgb) || source.equals(extended))
                && (destination.equals(srgb) || destination.equals(extended))) return true;
        final ColorSpace linear = ColorSpace.get(ColorSpace.Named.LINEAR_SRGB);
        final ColorSpace linearExtended = ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB);
        return (source.equals(linear) || source.equals(linearExtended))
                && (destination.equals(linear) || destination.equals(linearExtended));
    }

    private static void copyPixel(ByteBuffer target, int to, int bytes, ByteBuffer source, int from,
            int sourceBytes, ColorSpace.Connector connector, float[] rgb) {
        if (connector == null && bytes == sourceBytes) {
            if (bytes == 8) target.putLong(to, source.getLong(from));
            else target.putInt(to, source.getInt(from));
            return;
        }
        for (int channel = 0; channel < 3; channel++) {
            rgb[channel] = sourceBytes == 8 ? Half.toFloat(source.getShort(from + channel * 2))
                    : (source.get(from + channel) & 255) / 255f;
        }
        if (connector != null) connector.transform(rgb);
        for (int channel = 0; channel < 3; channel++) {
            if (bytes == 8) target.putShort(to + channel * 2, Half.toHalf(rgb[channel]));
            else target.put(to + channel,
                    (byte) Math.round(Math.max(0, Math.min(1, rgb[channel])) * 255));
        }
        final float alpha = sourceBytes == 8 ? Half.toFloat(source.getShort(from + 6))
                : (source.get(from + 3) & 255) / 255f;
        if (bytes == 8) target.putShort(to + 6, Half.toHalf(alpha));
        else target.put(to + 3, (byte) Math.round(alpha * 255));
    }

    private GateStraightPixels() { }
}
