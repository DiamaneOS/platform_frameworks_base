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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Bounds raw staging before any VM probe, including inputs that grow after their descriptor was checked. */
public final class BoundedImageInput {
    public static final class TooLarge extends IOException {
        public TooLarge() {
            super("image input exceeds the VM input limit");
        }
    }

    private BoundedImageInput() {
    }

    public static long copy(InputStream in, OutputStream out, long declared, long limit) throws IOException {
        if (declared < 0 || declared > limit) {
            throw new TooLarge();
        }
        final byte[] buffer = new byte[64 * 1024];
        long copied = 0;
        for (;;) {
            final int n = in.read(buffer, 0, (int) Math.min(buffer.length, limit - copied + 1));
            if (n < 0) {
                return copied;
            }
            if (n == 0) {
                throw new IOException("image input made no progress");
            }
            if (n > limit - copied) {
                throw new TooLarge();
            }
            out.write(buffer, 0, n);
            copied += n;
        }
    }
}
