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

/**
 * How an app's image file is decoded in the VM, decided from its header before the VM is asked: the box to decode to,
 * the pixels that will come back (charged before the decode), and the nine-patch data that is kept. A nine-patch is
 * decoded at its own size only when that fits the layout box. A bigger one is decoded to fit the box and shown plain,
 * so no file can make the VM return more than the box. The VM's reply is checked against the box asked for, so for a
 * PNG the box is narrowed to the size its header needs: what is charged before the decode covers every reply the
 * check lets through. Plain Java, tested on the host.
 */
public final class ImagePlan {
    public final int boxWidth;
    public final int boxHeight;
    /** The most pixels the decode can return: always the box's (the file's size fitted into the layout box). */
    public final long pixels;
    /** The checked nine-patch data, or null when the image is shown plain. */
    public final NinePatchChunk chunk;
    /** The width the file stores, from its header, or 0 when it is not known (not a PNG). */
    public final int storedWidth;

    private ImagePlan(int boxWidth, int boxHeight, long pixels, NinePatchChunk chunk, int storedWidth) {
        this.boxWidth = boxWidth;
        this.boxHeight = boxHeight;
        this.pixels = pixels;
        this.chunk = chunk;
        this.storedWidth = storedWidth;
    }

    /** The plan for the file in {@code bytes[0..length)} in a layout box of {@code maxWidth} x {@code maxHeight}. */
    public static ImagePlan of(byte[] bytes, int length, int maxWidth, int maxHeight) {
        final int boxWidth = Math.max(1, maxWidth);
        final int boxHeight = Math.max(1, maxHeight);
        final int[] size = NinePatchChunk.pngSize(bytes, length);
        if (size == null) {
            // Not a PNG: the header is not read here, the box bounds the result.
            return new ImagePlan(boxWidth, boxHeight, (long) boxWidth * boxHeight, null, 0);
        }
        final int width = size[0];
        final int height = size[1];
        if (width <= boxWidth && height <= boxHeight) {
            final NinePatchChunk chunk = NinePatchChunk.fromPng(bytes, length);
            return new ImagePlan(width, height, (long) width * height, chunk, width);
        }
        final int[] fitted = fitted(width, height, boxWidth, boxHeight);
        return new ImagePlan(fitted[0], fitted[1], (long) fitted[0] * fitted[1], null, width);
    }

    /** The pixels of a {@code width} x {@code height} image scaled down to fit the box, rounded up. */
    public static long fittedPixels(int width, int height, int boxWidth, int boxHeight) {
        final int[] f = fitted(width, height, boxWidth, boxHeight);
        return (long) f[0] * f[1];
    }

    // The size of a width x height image scaled down to fit the box, in whole numbers: the side that limits is the
    // box's, the other rounded up (never below what the VM's fit gives).
    private static int[] fitted(int width, int height, int boxWidth, int boxHeight) {
        if (width <= boxWidth && height <= boxHeight) {
            return new int[] {width, height};
        }
        if ((long) width * boxHeight > (long) height * boxWidth) {
            final long h = Math.max(1, ((long) height * boxWidth + width - 1) / width);
            return new int[] {boxWidth, (int) Math.min(boxHeight, h)};
        }
        final long w = Math.max(1, ((long) width * boxHeight + height - 1) / height);
        return new int[] {(int) Math.min(boxWidth, w), boxHeight};
    }
}
