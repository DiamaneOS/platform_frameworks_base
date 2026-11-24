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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** The decode client's health probes: one diagnostic slot across bindings, and what an answer says. */
@RunWith(JUnit4.class)
public class HealthProbesTest {

    @Test
    public void oneProbeCallAtATimeWhateverTheBinding() throws Exception {
        // The health check of a new binding and the recovery watch start together while nothing runs: one probe.
        for (int round = 0; round < 1_000; round++) {
            final HealthProbes probes = new HealthProbes();
            final CyclicBarrier together = new CyclicBarrier(3);
            final AtomicInteger started = new AtomicInteger();
            final Thread[] askers = new Thread[2];
            for (int i = 0; i < askers.length; i++) {
                askers[i] = new Thread(() -> {
                    try {
                        together.await();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    if (probes.start()) {
                        started.incrementAndGet();
                    }
                });
                askers[i].start();
            }
            together.await();
            for (Thread t : askers) {
                t.join(10_000);
            }
            assertEquals("probe calls started in round " + round, 1, started.get());
        }
    }

    @Test
    public void theSlotIsHeldUntilTheCallEnds() {
        final HealthProbes probes = new HealthProbes();
        assertTrue(probes.start());
        // The waiter gave up after its three seconds: the call still runs in a stopped service.
        assertTrue(probes.running());
        assertFalse("no second probe beside a stuck one, of any binding", probes.start());
        probes.ended();
        assertFalse(probes.running());
        assertTrue("free once the call ended", probes.start());
    }

    @Test
    public void onlyTheHealthImagesHeaderIsAnAnswer() {
        assertEquals(HealthProbes.Verdict.ANSWERS, HealthProbes.verdict(true, true, false, 1, 1, true));
    }

    @Test
    public void wrongAnswersForTheKnownImageAreNoRecovery() {
        assertEquals("invalid or unsupported for the health image", HealthProbes.Verdict.DOWN,
                HealthProbes.verdict(true, false, false, 0, 0, false));
        assertEquals("an unknown status", HealthProbes.Verdict.DOWN,
                HealthProbes.verdict(true, false, false, 1, 1, true));
        assertEquals("another size", HealthProbes.Verdict.DOWN, HealthProbes.verdict(true, true, false, 2, 1, true));
        assertEquals("another format", HealthProbes.Verdict.DOWN, HealthProbes.verdict(true, true, false, 1, 1, false));
        assertEquals("no reply", HealthProbes.Verdict.DOWN, HealthProbes.verdict(false, true, false, 1, 1, true));
    }

    @Test
    public void noRoomIsNeitherAnAnswerNorAnOutage() {
        assertEquals(HealthProbes.Verdict.NO_ROOM, HealthProbes.verdict(true, false, true, 0, 0, false));
    }
    @org.junit.Test
    public void everyHealthSourceHasAFreshDigest() throws Exception {
        final byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYGD4DwABBAEAX+XDSwAAAABJRU5ErkJggg==");
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 128; i++) {
            final byte[] challenge = HealthProbes.fresh(png);
            final String digest = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(challenge));
            org.junit.Assert.assertTrue("a warm header cannot answer the next probe", seen.add(digest));
            final java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(challenge);
            b.position(8);
            while (b.remaining() > 0) {
                final int n = b.getInt();
                final byte[] chunk = new byte[n + 4];
                b.get(chunk);
                final java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(chunk);
                org.junit.Assert.assertEquals("valid PNG chunk", (int) crc.getValue(), b.getInt());
            }
        }
    }
}
