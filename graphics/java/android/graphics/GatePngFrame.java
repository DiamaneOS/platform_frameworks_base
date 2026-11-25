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

package android.graphics;

/** DiamaneOS: bounds PNG stream reads without interpreting or validating compressed pixels. */
final class GatePngFrame {
    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private int signature;
    private int header;
    private long length;
    private int type;
    private long remaining;
    private boolean first = true;
    private boolean end;
    private boolean disabled;
    private boolean complete;

    int readLimit(int available) {
        if (disabled) return available;
        if (complete) return 0;
        if (signature < SIGNATURE.length) return Math.min(available, SIGNATURE.length - signature);
        if (header < 8) return Math.min(available, 8 - header);
        return (int) Math.min(available, remaining);
    }

    boolean complete() {
        return complete;
    }

    void accept(byte[] bytes, int count) {
        if (disabled || complete) return;
        for (int i = 0; i < count && !disabled && !complete; i++) {
            final int value = bytes[i] & 255;
            if (signature < SIGNATURE.length) {
                if (value != (SIGNATURE[signature++] & 255)) disabled = true;
            } else if (header < 8) {
                if (header < 4) length = (length << 8) | value;
                else type = (type << 8) | value;
                if (++header == 8) {
                    // Invalid framing remains subject to the normal bounded copy and VM refusal.
                    if (length > Integer.MAX_VALUE || (first && (type != 0x49484452 || length != 13))) {
                        disabled = true;
                    } else {
                        first = false;
                        end = type == 0x49454e44 && length == 0;
                        remaining = length + 4;
                    }
                }
            } else if (--remaining == 0) {
                // Include the four CRC bytes, the complete framed input still goes to the VM.
                complete = end;
                header = 0;
                length = 0;
                type = 0;
            }
        }
    }
}
