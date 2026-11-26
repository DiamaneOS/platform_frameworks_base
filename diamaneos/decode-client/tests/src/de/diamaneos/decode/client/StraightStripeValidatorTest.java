/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public class StraightStripeValidatorTest {
    private static byte[] half(int red, int alpha) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) red).putShort((short) 0).putShort((short) 0).putShort((short) alpha).array();
    }

    @Test public void explicitStraightBytesKeepTransparentRgb() throws Exception {
        byte[] rgba = {(byte) 255, 0, (byte) 128, 0};
        assertEquals(1, StripeValidator.checkHeader(1, 1, 1, 0, 1, 1, 2));
        assertEquals(4, StripeValidator.checkStripe(1, 1, 1, 0, rgba, 2));
        assertThrows(StripeValidator.BadReplyException.class,
                () -> StripeValidator.checkStripe(1, 1, 1, 0, rgba, 0));
    }

    @Test public void explicitStraightF16KeepsColorAboveAlpha() throws Exception {
        byte[] rgba = half(0x3c00, 0);
        assertEquals(1, StripeValidator.checkHeader(1, 1, 1, 0, 1, 1, 3));
        assertEquals(8, StripeValidator.checkStripe(1, 1, 1, 0, rgba, 3));
        assertThrows(StripeValidator.BadReplyException.class,
                () -> StripeValidator.checkStripe(1, 1, 1, 0, rgba, 1));
    }

    @Test public void straightF16StillRejectsEveryNonfiniteAndOutOfRangeValue() {
        for (int bits : new int[] {0x7e00, 0x7c00, 0x8000, 0x8001, 0x3c01}) {
            assertThrows(StripeValidator.BadReplyException.class,
                    () -> StripeValidator.checkStripe(1, 1, 1, 0, half(bits, 0), 3));
            assertThrows(StripeValidator.BadReplyException.class,
                    () -> StripeValidator.checkStripe(1, 1, 1, 0, half(0, bits), 3));
        }
    }

    @Test public void unknownStorageAndWrongLengthsRemainRefusals() {
        for (int format : new int[] {-1, 4, 256, Integer.MAX_VALUE}) {
            assertThrows(StripeValidator.BadReplyException.class,
                    () -> StripeValidator.checkHeader(1, 1, 1, 0, 1, 1, format));
        }
        for (int length : new int[] {0, 3, 5, 7, 9}) {
            assertThrows(StripeValidator.BadReplyException.class,
                    () -> StripeValidator.checkStripe(1, 1, 1, 0, new byte[length], 3));
        }
    }
}
