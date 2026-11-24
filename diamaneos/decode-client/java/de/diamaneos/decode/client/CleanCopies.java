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

import android.graphics.Bitmap;

/**
 * Where an image gate ({@link AppResourceImages#setStore}) finds clean copies of apps' image files: the pixels the VM
 * made for one file of one APK, by the gate's cache key. The system server keeps them ({@link CleanImageStore}) and
 * other system processes read them through it ({@link CleanImages}).
 */
public interface CleanCopies {
    /** One copy: its pixels and its nine-patch data. */
    final class Entry {
        public final Bitmap bitmap;
        public final NinePatchChunk chunk;

        public Entry(Bitmap bitmap, NinePatchChunk chunk) {
            this.bitmap = bitmap;
            this.chunk = chunk;
        }
    }

    /** The copy under {@code key}, or null. */
    Entry load(String key);

    /**
     * The newest copy of the same resource as the file {@code file} of the APK at {@code apkPath} (the resource
     * {@code resourceName} of the app the system server finds the APK installed for, as {@code userId}), the closest
     * to {@code density}, or null: shown while the exact copy is made.
     */
    default Entry fallback(String apkPath, String file, int density, int maxWidth, int maxHeight,
            String resourceName, int userId) {
        return null;
    }

    /** Keeps a copy this process made. Only the system server's store keeps them: elsewhere nothing happens. */
    default void save(String key, Bitmap bitmap, NinePatchChunk chunk) {
    }

    /** {@link #save}, unless a copy is kept already. */
    default void saveIfMissing(String key, Bitmap bitmap, NinePatchChunk chunk) {
    }

    /**
     * A gate that may not ask the VM found no copy of the file {@code file} of the APK at {@code apkPath} (density
     * {@code density}, box {@code maxWidth} x {@code maxHeight}), loaded as the resource {@code resourceName} as
     * {@code userId}: a store that can have it made does so, in the background. The system server finds the app the
     * APK belongs to itself.
     */
    default void missed(String key, String apkPath, String file, int density, int maxWidth, int maxHeight,
            String resourceName, int userId) {
    }
}
