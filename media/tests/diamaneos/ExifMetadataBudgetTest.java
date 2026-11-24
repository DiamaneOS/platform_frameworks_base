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

package android.media;

import static org.junit.Assert.assertThrows;
import org.junit.Test;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.io.DataInputStream;

public class ExifMetadataBudgetTest {
    @Test
    public void invalidExtentAndAggregateAllocationFailBeforeAllocation() throws Exception {
        final ExifMetadataBudget budget = new ExifMetadataBudget();
        assertThrows(IOException.class, () -> budget.allocate(Integer.MAX_VALUE, 4));
        assertThrows(IOException.class, () -> budget.allocate(-1, 4));
        budget.allocate(4 * 1024 * 1024, 8 * 1024 * 1024);
        budget.allocate(4 * 1024 * 1024, 8 * 1024 * 1024);
        assertThrows(IOException.class, () -> budget.allocate(1, 8 * 1024 * 1024));
        assertThrows(IOException.class, () -> budget.chunk(64, 32));
    }

    @Test
    public void nestedDirectoriesAndChunkCountAreBounded() throws Exception {
        final ExifMetadataBudget nesting = new ExifMetadataBudget();
        for (int i = 0; i < 64; ++i) nesting.enter();
        assertThrows(IOException.class, nesting::enter);
        final ExifMetadataBudget work = new ExifMetadataBudget();
        for (int i = 0; i < 16384; ++i) work.visit();
        assertThrows(IOException.class, work::visit);
    }

    @Test
    public void numericComponentsShareTheWorkAllowance() throws Exception {
        final ExifMetadataBudget budget = new ExifMetadataBudget();
        budget.components(10000);
        assertThrows(IOException.class, () -> budget.components(10000));
        budget.reset();
        budget.components(10000);
    }

    @Test
    public void skippedPixelsDoNotKeepAnUnlimitedRewindWindow() throws Exception {
        final InputStream source = new InputStream() {
            private int remaining = 16 * 1024 * 1024;
            public int available() { return remaining; }
            public int read() { if (remaining == 0) return -1; --remaining; return 0; }
            public int read(byte[] bytes, int offset, int length) {
                if (remaining == 0) return -1;
                final int n = Math.min(length, remaining);
                java.util.Arrays.fill(bytes, offset, offset + n, (byte) 0);
                remaining -= n;
                return n;
            }
            public long skip(long length) {
                final long n = Math.min(length, remaining);
                remaining -= (int) n;
                return n;
            }
        };
        try (BufferedInputStream buffer = ExifMetadataBudget.buffered(source, 16);
                DataInputStream input = new DataInputStream(buffer)) {
            input.mark(input.available());
            org.junit.Assert.assertEquals(12 * 1024 * 1024, input.skipBytes(12 * 1024 * 1024));
            assertThrows(ExifMetadataBudget.Limit.class, input::reset);
        }
    }

    @Test
    public void thumbnailExtentsAndLongStripSumsAreCheckedBeforeAllocation() throws Exception {
        final ExifMetadataBudget budget = new ExifMetadataBudget();
        assertThrows(IOException.class, () -> budget.allocateAt(4, -1, 8));
        assertThrows(IOException.class, () -> budget.allocateAt(4, 6, 8));
        assertThrows(IOException.class, () -> budget.allocateStrips(
                new long[]{1}, new long[]{Integer.MAX_VALUE}, 8));
        assertThrows(IOException.class, () -> budget.allocateStrips(
                new long[]{0, 0, 0}, new long[]{Integer.MAX_VALUE, Integer.MAX_VALUE, 2},
                Integer.MAX_VALUE));
        org.junit.Assert.assertEquals(8, budget.allocateStrips(
                new long[]{0, 4}, new long[]{4, 4}, 8).length);
    }

    @Test
    public void thumbnailStripsShareTheAttributeAllowance() throws Exception {
        final ExifMetadataBudget budget = new ExifMetadataBudget();
        budget.allocate(8 * 1024 * 1024 - 4, 8 * 1024 * 1024);
        org.junit.Assert.assertEquals(4, budget.allocateStrips(
                new long[]{0}, new long[]{4}, 4).length);
        assertThrows(ExifMetadataBudget.Limit.class, () -> budget.allocateStrips(
                new long[]{0}, new long[]{1}, 1));
    }
}
