/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.media;

import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedInputStream;

/** @hide Per-file budget for host metadata parsing and retained attribute bytes. */
final class ExifMetadataBudget {
    private static final int MAX_STEPS = 16384;
    private static final int MAX_DEPTH = 64;
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private int mSteps;
    private int mDepth;
    private long mBytes;

    static final class Limit extends IOException {
        Limit(String message) { super(message); }
        Limit(String message, IOException cause) { super(message, cause); }
    }

    static BufferedInputStream buffered(InputStream source, int size) {
        return new BufferedInputStream(source, size) {
            @Override public synchronized void mark(int limit) {
                super.mark(Math.min(limit, (int) MAX_BYTES));
            }
            @Override public synchronized void reset() throws IOException {
                try { super.reset(); }
                catch (IOException e) { throw new Limit("EXIF rewind limit", e); }
            }
        };
    }

    void reset() {
        mSteps = 0;
        mDepth = 0;
        mBytes = 0;
    }

    void components(int count) throws IOException {
        if (count < 0 || count > MAX_STEPS - mSteps) throw new Limit("EXIF component limit");
        mSteps += count;
    }

    void visit() throws IOException {
        if (++mSteps > MAX_STEPS) throw new Limit("EXIF traversal limit");
    }

    void enter() throws IOException {
        visit();
        if (mDepth >= MAX_DEPTH) throw new Limit("EXIF nesting limit");
        ++mDepth;
    }

    void exit() {
        --mDepth;
    }

    void chunk(int length, long remaining) throws IOException {
        visit();
        if (length < 0 || length > remaining) throw new IOException("Invalid metadata extent");
    }

    byte[] allocate(int length, long remaining) throws IOException {
        if (length < 0 || length > remaining) throw new IOException("Invalid metadata extent");
        if (length > MAX_BYTES - mBytes) throw new Limit("EXIF allocation limit");
        mBytes += length;
        return new byte[length];
    }

    byte[] allocateAt(int length, long offset, long sourceLength) throws IOException {
        checkAt(length, offset, sourceLength);
        return allocate(length, sourceLength - offset);
    }

    void checkAt(int length, long offset, long sourceLength) throws IOException {
        if (offset < 0 || offset > sourceLength) throw new IOException("Invalid thumbnail offset");
        if (length < 0 || length > sourceLength - offset) throw new IOException("Invalid thumbnail extent");
        if (length > MAX_BYTES - mBytes) throw new Limit("EXIF thumbnail limit");
    }

    byte[] allocateStrips(long[] offsets, long[] counts, long sourceLength) throws IOException {
        if (sourceLength < 0 || offsets.length != counts.length) throw new IOException("Invalid thumbnail strips");
        long total = 0;
        for (int i = 0; i < offsets.length; i++) {
            visit();
            if (offsets[i] < 0 || offsets[i] > sourceLength || counts[i] < 0
                    || counts[i] > sourceLength - offsets[i]) {
                throw new IOException("Invalid thumbnail strip extent");
            }
            if (counts[i] > MAX_BYTES - mBytes - total) throw new Limit("EXIF thumbnail limit");
            total += counts[i];
        }
        return allocate((int) total, total);
    }
}
