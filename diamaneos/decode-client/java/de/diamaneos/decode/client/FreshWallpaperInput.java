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

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

/** Opens the next source before publishing it at the canonical path. */
public final class FreshWallpaperInput {
    private FreshWallpaperInput() {
    }

    public interface Opener<T> {
        T open(File staged) throws IOException;
    }

    public interface Publisher {
        void publish(File staged) throws IOException;
    }

    public static <T extends Closeable> T replace(File source, Opener<T> opener, Publisher publisher)
            throws IOException {
        final File staged = File.createTempFile(source.getName(), ".vmset", source.getParentFile());
        T input = null;
        boolean published = false;
        try {
            input = opener.open(staged);
            publisher.publish(staged);
            published = true;
            return input;
        } finally {
            if (!published) {
                try {
                    if (input != null) input.close();
                } finally {
                    staged.delete();
                }
            }
        }
    }
}
