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
import static org.junit.Assert.assertThrows;

import de.diamaneos.decode.client.StripeValidator.BadReplyException;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** The checks on what the decode service sends back. Plain Java, no device state needed. */
@RunWith(JUnit4.class)
public class StripeValidatorTest {

    @Test
    public void headerWithinBox() throws Exception {
        assertEquals(3, StripeValidator.checkHeader(100, 50, 20, 0, 100, 100));
        assertEquals(1, StripeValidator.checkHeader(1, 1, 1, 0, 1, 1));
        // Turned images are checked against the box the other way round.
        assertEquals(1, StripeValidator.checkHeader(50, 100, 100, 90, 100, 50));
    }

    @Test
    public void headerRejectsBadValues() {
        assertThrows(BadReplyException.class, () -> StripeValidator.checkHeader(0, 10, 1, 0, 100, 100));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkHeader(10, -1, 1, 0, 100, 100));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkHeader(101, 10, 1, 0, 100, 100));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkHeader(50, 100, 10, 90, 99, 100));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkHeader(10, 10, 1, 45, 100, 100));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkHeader(10, 10, 0, 0, 100, 100));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkHeader(10, 10, 11, 0, 100, 100));
        // Too many pixels even within the box.
        assertThrows(BadReplyException.class, () -> StripeValidator.checkHeader(4000, 4000, 1, 0, 4000, 4000));
        // A stripe over the byte limit.
        assertThrows(BadReplyException.class, () -> StripeValidator.checkHeader(1000, 1000, 100, 0, 1000, 1000));
        // Sizes past the dimension limit, where width * height would overflow an int.
        assertThrows(BadReplyException.class,
                () -> StripeValidator.checkHeader(70000, 70000, 1, 0, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkHeader(10, 10, 1, 0, 0, 10));
    }

    @Test
    public void stripeLengths() throws Exception {
        assertEquals(20 * 100 * 4, StripeValidator.stripeLength(100, 50, 20, 0));
        assertEquals(20 * 100 * 4, StripeValidator.stripeLength(100, 50, 20, 1));
        assertEquals(10 * 100 * 4, StripeValidator.stripeLength(100, 50, 20, 2));
        assertThrows(BadReplyException.class, () -> StripeValidator.stripeLength(100, 50, 20, 3));
        assertThrows(BadReplyException.class, () -> StripeValidator.stripeLength(100, 50, 20, -1));
    }

    @Test
    public void putStripeChecksLengthAndRefusesColourAboveAlpha() throws Exception {
        final byte[] frame = new byte[2 * 2 * 4];
        final byte[] first = {(byte) 100, 10, 20, (byte) 100, 1, 2, 3, (byte) 255};
        StripeValidator.putStripe(frame, 2, 2, 1, 0, first);
        assertArrayEquals(first, java.util.Arrays.copyOfRange(frame, 0, 8));
        // A colour channel above alpha is not a premultiplied pixel: refused, not clamped (DecodeChecks).
        final byte[] bad = {(byte) 200, 10, 20, (byte) 100, 1, 2, 3, (byte) 255};
        assertThrows(BadReplyException.class, () -> StripeValidator.putStripe(frame, 2, 2, 1, 1, bad));
        assertThrows(BadReplyException.class, () -> StripeValidator.putStripe(frame, 2, 2, 1, 1, new byte[7]));
        assertThrows(BadReplyException.class, () -> StripeValidator.putStripe(frame, 2, 2, 1, 1, new byte[9]));
        assertThrows(BadReplyException.class, () -> StripeValidator.putStripe(frame, 2, 2, 1, 1, null));
        assertThrows(BadReplyException.class, () -> StripeValidator.putStripe(frame, 2, 2, 1, 2, new byte[8]));
        assertThrows(BadReplyException.class, () -> StripeValidator.putStripe(new byte[3], 2, 2, 1, 0, first));
    }

    @Test
    public void infoChecks() throws Exception {
        StripeValidator.checkInfo(1, 640, 480);
        assertThrows(BadReplyException.class, () -> StripeValidator.checkInfo(11, 640, 480));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkInfo(-1, 640, 480));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkInfo(1, 0, 480));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkInfo(1, 640, 65536));
        StripeValidator.checkInfo(3, 640, 480, true);
        StripeValidator.checkInfo(4, 640, 480, true);
        StripeValidator.checkInfo(2, 640, 480, true);
        StripeValidator.checkInfo(6, 640, 480, true);
        StripeValidator.checkInfo(7, 640, 480, true);
        assertThrows(BadReplyException.class, () -> StripeValidator.checkInfo(1, 640, 480, true));
        assertThrows(BadReplyException.class, () -> StripeValidator.checkInfo(5, 640, 480, true));
    }
}
