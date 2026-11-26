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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.Collections;

/** The size of a wallpaper's clean copy and its crop hints in it. */
@RunWith(JUnit4.class)
public class WallpaperBoxTest {
    private static final int MAX_BOX = 16_384;

    @Test
    public void noCrop_coversTheDisplayEitherWay() {
        // The whole 4000 x 3000 image: 2400 / 3000 is the scale that covers a 1080 x 2400 display either way.
        assertEquals(3200, WallpaperBox.side(4000, 3000, 1080, 2400, Collections.emptyList(), MAX_BOX));
        assertEquals(3200, WallpaperBox.side(4000, 3000, 1080, 2400, null, MAX_BOX));
    }

    @Test
    public void eachHintGetsItsOwnScale_notTheirUnion() {
        // Two hints far apart: their union needs only 1000 / 6000, but the small hint needs 1000 / 2000.
        final int side = WallpaperBox.side(8000, 8000, 1000, 1000,
                Arrays.asList(new int[] {0, 0, 4000, 4000}, new int[] {4000, 4000, 6000, 6000}), MAX_BOX);
        assertEquals(4000, side);
        // The small hint then has 1000 pixels on each side: not blurry.
        final double scale = side / 8000.0;
        assertEquals(1000, (int) Math.round(2000 * scale));
    }

    @Test
    public void neverAboveTheOriginalOrTheBox() {
        assertEquals(6000, WallpaperBox.side(6000, 6000, 1080, 2400,
                Collections.singletonList(new int[] {0, 0, 1200, 2400}), MAX_BOX));
        assertEquals(4096, WallpaperBox.side(6000, 6000, 1080, 2400,
                Collections.singletonList(new int[] {0, 0, 1200, 2400}), 4096));
    }

    @Test
    public void emptyOrOutsideHint_countsAsTheWholeImage() {
        final int whole = WallpaperBox.side(4000, 3000, 1080, 2400, null, MAX_BOX);
        assertEquals(whole, WallpaperBox.side(4000, 3000, 1080, 2400,
                Collections.singletonList(new int[] {10, 10, 10, 10}), MAX_BOX));
        assertEquals(whole, WallpaperBox.side(4000, 3000, 1080, 2400,
                Collections.singletonList(new int[] {5000, 5000, 6000, 6000}), MAX_BOX));
    }

    @Test
    public void unknownSize_displaySide() {
        assertEquals(2400, WallpaperBox.side(0, 0, 1080, 2400, null, MAX_BOX));
    }

    @Test
    public void largest_perSideOverAllSizes() {
        // A fold: the inner screen is wider, the cover screen is taller.
        assertArrayEquals(new int[] {1800, 2400},
                WallpaperBox.largest(Arrays.asList(new int[] {1080, 2400}, new int[] {2000, 1800})));
        assertArrayEquals(new int[] {0, 0}, WallpaperBox.largest(null));
    }

    @Test
    public void map_turnsAndScales() {
        // No turn, half size.
        assertArrayEquals(new int[] {50, 25, 100, 75},
                WallpaperBox.map(new int[] {100, 50, 200, 150}, 400, 300, 0, 200, 150));
        // Turned 90 degrees clockwise, same size.
        assertArrayEquals(new int[] {180, 0, 200, 10},
                WallpaperBox.map(new int[] {0, 0, 10, 20}, 100, 200, 90, 200, 100));
        // Turned 180 degrees.
        assertArrayEquals(new int[] {90, 180, 100, 200},
                WallpaperBox.map(new int[] {0, 0, 10, 20}, 100, 200, 180, 100, 200));
        // Turned 270 degrees.
        assertArrayEquals(new int[] {0, 90, 20, 100},
                WallpaperBox.map(new int[] {0, 0, 10, 20}, 100, 200, 270, 200, 100));
        // Rounded outwards and clipped to the copy.
        assertArrayEquals(new int[] {0, 0, 34, 34},
                WallpaperBox.map(new int[] {0, 0, 100, 100}, 300, 300, 0, 100, 100));
        assertArrayEquals(new int[] {90, 90, 100, 100},
                WallpaperBox.map(new int[] {270, 270, 400, 400}, 300, 300, 0, 100, 100));
    }
}
