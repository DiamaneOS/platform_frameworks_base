/* Copyright (C) 2026 DiamaneOS */
package com.android.internal.diamaneos.cleancopies;
import static org.junit.Assert.*;
import org.junit.Test;
public class ImageAttemptsTest {
    @Test public void onlyTheLatestAttemptCanPublishForThatUri() {
        ImageAttempts<Object> attempts = new ImageAttempts<>();
        ImageAttempts.Ticket<Object> old = attempts.begin("uri", new Object());
        ImageAttempts.Ticket<Object> other = attempts.begin("other", new Object());
        assertTrue(attempts.current(old));
        ImageAttempts.Ticket<Object> fresh = attempts.begin("uri", new Object());
        assertFalse(attempts.current(old));
        assertTrue(attempts.current(fresh));
        assertTrue(attempts.current(other));
    }

    @Test public void capPreservesLiveTicketsAndReopensAfterDelivery() {
        ImageAttempts<Object> attempts = new ImageAttempts<>();
        ImageAttempts.Ticket<Object> oldest = attempts.begin("first", new Object());
        for (int i = 1; i < 512; i++) assertNotNull(attempts.begin("key" + i, new Object()));
        assertNull(attempts.begin("waiting", new Object()));
        assertTrue(attempts.current(oldest));
        oldest.delivered = true;
        assertNotNull(attempts.begin("waiting", new Object()));
        assertFalse(attempts.current(oldest));
    }
}
