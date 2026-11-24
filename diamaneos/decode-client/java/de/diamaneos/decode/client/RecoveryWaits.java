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
 * The waits of the decode client's recovery watch between its probes: from {@link #FIRST_MS}, doubling up to
 * {@link #MAX_MS}. After {@link #TRIES} probes a watch of a service that is down goes on at {@link #SLOW_MS}, so a
 * service that comes back late is still found (what was left out waits for it). A watch of a service that answers but
 * has no room ends then: its next answers end that trouble. Plain Java, tested on the host.
 */
final class RecoveryWaits {
    static final long FIRST_MS = 1_000;
    static final long MAX_MS = 30_000;
    static final int TRIES = 20;
    static final long SLOW_MS = 300_000;
    /** From {@link #next}: the watch ends. */
    static final long END = -1;

    private RecoveryWaits() {
    }

    /** The wait before the next probe after {@code tries} probes, the last wait {@code wait}, or {@link #END}. */
    static long next(int tries, long wait, boolean down) {
        if (tries >= TRIES) {
            return down ? SLOW_MS : END;
        }
        return Math.min(wait * 2, MAX_MS);
    }
}
