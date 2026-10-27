/*
 * Copyright (C) 2026 The DiamaneOS Project
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

package com.android.internal.widget;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** Plain JUnit: needs no device. */
public class ShortListPasswordsTest {

    private static byte[] bytes(char[] letters) {
        return new String(letters).getBytes(StandardCharsets.US_ASCII);
    }

    private static boolean isShortList(String letters) {
        return ShortListPasswords.isShortListPassword(letters.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void generate_isEighteenLowercaseLettersOfSixPrefixes() {
        assertEquals(18, ShortListPasswords.LENGTH);
        final Random[] sources = {new Random(20261027), new SecureRandom()};
        for (Random random : sources) {
            for (int i = 0; i < 20_000; i++) {
                final char[] letters = new char[18];
                ShortListPasswords.generate(random, 5, letters);
                for (char c : letters) {
                    assertTrue("character " + (int) c, c >= 'a' && c <= 'z');
                }
                assertTrue(ShortListPasswords.isShortListPassword(bytes(letters)));
                assertTrue(new String(letters).chars().distinct().count() >= 5);
            }
        }
    }

    @Test
    public void generate_everyPrefixEquallyLikelyInEveryPosition() {
        // 100 expected per prefix and position. Chi-square within five standard deviations.
        final Random random = new Random(20261027);
        final int count = ShortListPasswords.PREFIX_COUNT;
        final Map<String, Integer> index = new HashMap<>();
        final long[][] counts = new long[6][count];
        for (int i = 0; i < count * 100; i++) {
            final char[] letters = new char[18];
            ShortListPasswords.generate(random, 5, letters);
            for (int position = 0; position < 6; position++) {
                final String prefix = new String(letters, position * 3, 3);
                index.putIfAbsent(prefix, index.size());
                counts[position][index.get(prefix)]++;
            }
        }

        assertEquals(count, index.size());
        for (long[] cells : counts) {
            double chiSquare = 0;
            for (long cell : cells) {
                chiSquare += (cell - 100.0) * (cell - 100.0) / 100.0;
            }
            assertTrue("chi-square " + chiSquare,
                    Math.abs(chiSquare - (count - 1)) <= 5 * Math.sqrt(2.0 * (count - 1)));
        }
    }

    @Test
    public void generate_underTheFloor_drawsAllSixAgain() {
        final char[] letters = new char[18];
        // aardvark six times has two different letters: the next six words are drawn.
        final Random scripted = new Random() {
            private int mCalls;

            @Override
            public int nextInt(int bound) {
                // Then uncle abandoned fox gem oat pry, by their indices.
                final int[] script = {0, 0, 0, 0, 0, 0, 1155, 1, 464, 482, 806, 909};
                return script[mCalls++];
            }
        };

        ShortListPasswords.generate(scripted, 5, letters);

        assertArrayEquals("uncabafoxgemoatpry".toCharArray(), letters);
        assertThrows(IllegalArgumentException.class,
                () -> ShortListPasswords.generate(new Random(), 5, new char[17]));
    }

    @Test
    public void isShortListPassword_onlyTheExactShape() {
        assertTrue(isShortList("uncabafoxgemoatpry"));
        assertTrue(isShortList("aarzucaarzucaarzuc"));
        assertFalse(isShortList("uncabafoxgemoat"));
        assertFalse(isShortList("uncabafoxgemoatprya"));
        assertFalse(isShortList("uncabafoxgemoatqqq"));
        assertFalse(isShortList("uncabafoxgemoatyo-"));
        assertFalse(isShortList("UNCabafoxgemoatpry"));
        assertFalse(isShortList("uncabafoxgemoatpré"));
        assertFalse(isShortList(""));
    }
}
