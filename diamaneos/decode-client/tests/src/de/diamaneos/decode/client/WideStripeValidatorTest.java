/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;
import org.junit.Test;

/** Independent stripe validation rejects malformed wide storage before bitmap construction. */
public class WideStripeValidatorTest {
    private interface Checked { void run() throws Exception; }
    private static void rejected(Checked operation) throws Exception {
        try { operation.run(); fail("malformed stripe accepted"); }
        catch (StripeValidator.BadReplyException expected) { }
    }
    private static byte[] half(int red, int alpha) {
        return new byte[] {(byte) red, (byte) (red >>> 8), 0, 0, 0, 0, (byte) alpha, (byte) (alpha >>> 8)};
    }

    @Test public void completeWideStripeAndLegacyBytes() throws Exception {
        assertEquals(1, StripeValidator.checkHeader(1, 1, 1, 0, 1, 1, 1));
        assertEquals(8, StripeValidator.checkStripe(1, 1, 1, 0, half(0x3400, 0x3800), 1));
        assertEquals(8, StripeValidator.stripeLength(1, 1, 1, 0, 1));
        assertEquals(4, StripeValidator.checkStripe(1, 1, 1, 0, new byte[] {1, 2, 3, 4}));
        rejected(() -> StripeValidator.checkStripe(1, 1, 1, 0, new byte[] {5, 2, 3, 4}));
    }

    @Test public void wideValuesMustBeFiniteUnitPremultiplied() throws Exception {
        for (int value : new int[] {0x7e00, 0x7c00, 0x8000, 0x8001, 0x3c01}) {
            rejected(() -> StripeValidator.checkStripe(1, 1, 1, 0, half(value, 0x3c00), 1));
        }
        rejected(() -> StripeValidator.checkStripe(1, 1, 1, 0, half(0x3801, 0x3800), 1));
        rejected(() -> StripeValidator.checkStripe(1, 1, 1, 0, half(0, 0x3c01), 1));
        assertEquals(8, StripeValidator.checkStripe(1, 1, 1, 0, half(1, 1), 1));
        assertEquals(8, StripeValidator.checkStripe(1, 1, 1, 0, half(0, 0), 1));
    }

    @Test public void widePixelsKeepTheSameByteCeilings() throws Exception {
        assertEquals(1, StripeValidator.checkHeader(32768, 1, 1, 0, 32768, 1, 1));
        rejected(() -> StripeValidator.checkHeader(32769, 1, 1, 0, 32769, 1, 1));
        assertEquals(128, StripeValidator.checkHeader(2048, 2048, 16, 0, 2048, 2048, 1));
        rejected(() -> StripeValidator.checkHeader(4096, 1025, 8, 0, 4096, 1025, 1));
        rejected(() -> StripeValidator.checkHeader(1, 1, 1, 0, 1, 1, 4));
        rejected(() -> StripeValidator.checkHeader(1, 1, 1, 0, 1, 1, -1));
    }

    @Test public void wrongByteCountAndIndexRefused() throws Exception {
        rejected(() -> StripeValidator.checkStripe(1, 1, 1, 0, new byte[7], 1));
        rejected(() -> StripeValidator.checkStripe(1, 1, 1, 0, new byte[9], 1));
        rejected(() -> StripeValidator.checkStripe(1, 1, 1, -1, half(0, 0), 1));
        rejected(() -> StripeValidator.checkStripe(1, 1, 1, 1, half(0, 0), 1));
    }
}
