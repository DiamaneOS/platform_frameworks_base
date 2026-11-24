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
 * The decode client's own checks of the service with its health image (a one pixel PNG it wrote): one diagnostic slot
 * in the whole client, across bindings, held until the probe's binder call itself ends, so a probe stuck in a stopped
 * service never has a second one beside it. And what an answer says: the service process may answer (Binder liveness)
 * and still have no room now, or answer something that is not the health image's header. Only a valid answer for the
 * expected image says the VM works. Plain Java, tested on the host.
 */
final class HealthProbes {
    /** What a probe of the health image found. */
    enum Verdict {
        /** The VM read the health image: a 1 x 1 PNG, status OK. */
        ANSWERS,
        /** The service answered that it has no room now: it runs, its VM's work is held up by other images. */
        NO_ROOM,
        /** No answer in time, the binder call failed, the VM is unavailable, or the answer is not the image's. */
        DOWN,
    }

    private boolean mRunning; // guarded by this

    /** Takes the diagnostic slot for a new probe call. False while a probe call of this client runs. */
    synchronized boolean start() {
        if (mRunning) {
            return false;
        }
        mRunning = true;
        return true;
    }

    /** The probe's binder call ended (or never started): the slot is free. */
    synchronized void ended() {
        mRunning = false;
    }

    /** True while a probe call of this client runs. */
    synchronized boolean running() {
        return mRunning;
    }

    /** A fresh source for each diagnostic so an earlier service cache entry cannot answer it. */
    static synchronized byte[] fresh(byte[] png) {
        if (png.length < 12) {
            throw new IllegalArgumentException("missing PNG end chunk");
        }
        if (sSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("health challenge sequence exhausted");
        }
        // Created on the diagnostic worker and published only after entropy initialization succeeds.
        if (sSalt == null) {
            final byte[] salt = new byte[16];
            new java.security.SecureRandom().nextBytes(salt);
            sSalt = salt;
        }
        final long sequence = ++sSequence;
        final java.nio.ByteBuffer result = java.nio.ByteBuffer.allocate(png.length + 12 + sSalt.length + 8);
        result.put(png, 0, png.length - 12);
        result.putInt(sSalt.length + 8);
        final int crcStart = result.position();
        result.putInt(0x64694167); // diAg, an ancillary private safe-to-copy chunk
        result.put(sSalt);
        result.putLong(sequence);
        final java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(result.array(), crcStart, 4 + sSalt.length + 8);
        result.putInt((int) crc.getValue());
        result.put(png, png.length - 12, 12);
        return result.array();
    }

    private static byte[] sSalt; // guarded by HealthProbes.class
    private static long sSequence; // guarded by HealthProbes.class

    /**
     * The verdict on a probe's reply: {@code replied} when the binder call returned a reply, its status (OK, no room
     * now, or another), and the size and whether the format is PNG.
     */
    static Verdict verdict(boolean replied, boolean statusOk, boolean statusNoRoom, int width, int height,
            boolean png) {
        if (!replied) {
            return Verdict.DOWN;
        }
        if (statusNoRoom) {
            return Verdict.NO_ROOM;
        }
        return statusOk && width == 1 && height == 1 && png ? Verdict.ANSWERS : Verdict.DOWN;
    }
}
