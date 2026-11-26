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

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.*;
import java.nio.file.Files;

public class FreshWallpaperInputTest {
    @Test
    public void alreadyIssuedDescriptor_cannotWriteTheNewSetAfterItsFinalStat() throws Exception {
        final File source = File.createTempFile("wallpaper", ".input");
        try (RandomAccessFile old = new RandomAccessFile(source, "rw")) {
            old.write(new byte[] {1, 1, 1});
            try (RandomAccessFile next = replace(source, false)) {
                next.write(new byte[] {2, 2, 2});
            }
            assertEquals(3, source.length());
            // The outstanding old FD writes after the new set's final file check.
            old.seek(0);
            old.write(new byte[] {9, 9, 9});
            assertArrayEquals(new byte[] {2, 2, 2}, Files.readAllBytes(source.toPath()));
        } finally {
            source.delete();
        }
    }

    @Test
    public void failedPreparation_keepsPreviousSource() throws Exception {
        final File source = File.createTempFile("wallpaper", ".input");
        try {
            final byte[] pixels = {1, 2, 3};
            Files.write(source.toPath(), pixels);
            try {
                replace(source, true);
                fail("expected preparation to fail");
            } catch (IOException expected) {
                assertArrayEquals(pixels, Files.readAllBytes(source.toPath()));
            }
        } finally {
            source.delete();
        }
    }

    private static RandomAccessFile replace(File source, boolean fail) throws IOException {
        return FreshWallpaperInput.replace(source, staged -> {
            if (fail) throw new IOException("fixture preparation failure");
            return new RandomAccessFile(staged, "rw");
        }, staged -> Files.move(staged.toPath(), source.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING));
    }
}
