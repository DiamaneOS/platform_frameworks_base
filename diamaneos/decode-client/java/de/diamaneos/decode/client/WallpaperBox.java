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

import java.util.List;

/**
 * The size the protected VM makes a wallpaper's clean copy at: just big enough that each crop hint covers the
 * display in either orientation, so no crop is blurry, and never bigger than the original. Plain Java with rectangles
 * as {@code {left, top, right, bottom}}, tested on the host.
 */
public final class WallpaperBox {
    private WallpaperBox() {
    }

    /**
     * The side of the square box to decode a {@code width} x {@code height} original into: its longest side scaled by
     * the largest scale any of {@code crops} needs to cover a {@code displayWidth} x {@code displayHeight} display
     * turned either way (a square box keeps the shape whatever turn the file asks for), at most 1 and at most
     * {@code maxBox}. A crop that is empty or outside the image counts as the whole image, as does an empty list.
     */
    public static int side(int width, int height, int displayWidth, int displayHeight, List<int[]> crops,
            int maxBox) {
        if (width <= 0 || height <= 0) {
            return Math.max(1, Math.min(Math.max(displayWidth, displayHeight), maxBox));
        }
        double scale = 0;
        boolean any = false;
        if (crops != null) {
            for (int[] crop : crops) {
                scale = Math.max(scale, scaleFor(width, height, displayWidth, displayHeight, crop));
                any = true;
            }
        }
        if (!any) {
            scale = scaleFor(width, height, displayWidth, displayHeight, null);
        }
        scale = Math.min(1.0, scale);
        final long side = (long) Math.ceil(Math.max(width, height) * scale);
        return (int) Math.max(1, Math.min(side, maxBox));
    }

    // The scale at which crop (clipped to the image, the whole image when empty) covers the display either way.
    private static double scaleFor(int width, int height, int displayWidth, int displayHeight, int[] crop) {
        int left = 0;
        int top = 0;
        int right = width;
        int bottom = height;
        if (crop != null && crop.length == 4) {
            final int l = Math.max(0, crop[0]);
            final int t = Math.max(0, crop[1]);
            final int r = Math.min(width, crop[2]);
            final int b = Math.min(height, crop[3]);
            if (r > l && b > t) {
                left = l;
                top = t;
                right = r;
                bottom = b;
            }
        }
        final double cw = right - left;
        final double ch = bottom - top;
        return Math.max(Math.max(displayWidth / cw, displayHeight / ch),
                Math.max(displayWidth / ch, displayHeight / cw));
    }

    /**
     * The display size to cover: the largest width and the largest height over all {@code sizes} ({@code {w, h}}),
     * each turned so the longer side is the height, so a fold's cover and inner screens are both covered.
     */
    public static int[] largest(List<int[]> sizes) {
        int shorter = 0;
        int longer = 0;
        if (sizes != null) {
            for (int[] s : sizes) {
                if (s == null || s.length != 2) {
                    continue;
                }
                shorter = Math.max(shorter, Math.min(s[0], s[1]));
                longer = Math.max(longer, Math.max(s[0], s[1]));
            }
        }
        return new int[] {shorter, longer};
    }

    /**
     * {@code r}, a rectangle of the {@code originalWidth} x {@code originalHeight} original's stored pixels, in the
     * pixels of the clean copy ({@code width} x {@code height}, turned clockwise by {@code rotation}). Rounded
     * outwards and clipped to the copy.
     */
    public static int[] map(int[] r, int originalWidth, int originalHeight, int rotation, int width, int height) {
        final int w = originalWidth;
        final int h = originalHeight;
        final int[] t;
        switch (rotation) {
            case 90:
                t = new int[] {h - r[3], r[0], h - r[1], r[2]};
                break;
            case 180:
                t = new int[] {w - r[2], h - r[3], w - r[0], h - r[1]};
                break;
            case 270:
                t = new int[] {r[1], w - r[2], r[3], w - r[0]};
                break;
            default:
                t = new int[] {r[0], r[1], r[2], r[3]};
                break;
        }
        final boolean turned = rotation == 90 || rotation == 270;
        final double sx = (double) width / (turned ? h : w);
        final double sy = (double) height / (turned ? w : h);
        final int[] out = {(int) Math.floor(t[0] * sx), (int) Math.floor(t[1] * sy), (int) Math.ceil(t[2] * sx),
                (int) Math.ceil(t[3] * sy)};
        out[0] = Math.max(0, Math.min(out[0], width));
        out[1] = Math.max(0, Math.min(out[1], height));
        out[2] = Math.max(out[0], Math.min(out[2], width));
        out[3] = Math.max(out[1], Math.min(out[3], height));
        return out;
    }
}
