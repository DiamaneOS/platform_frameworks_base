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

package android.content.res;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.TypedValue;

/**
 * DiamaneOS: where ResourcesImpl and Resources consult a Resources object's {@link ImageFileGate}. Kept out of those
 * upstream classes, which only call in here.
 *
 * @hide
 */
final class ImageFileGates {
    private ImageFileGates() {
    }

    /** The path of the APK of {@code assets} that holds the files of {@code cookie}, or null when it is not known. */
    @Nullable
    static String apkPathForCookie(@NonNull AssetManager assets, int cookie) {
        final ApkAssets[] apkAssets = assets.getApkAssets();
        if (cookie < 1 || cookie > apkAssets.length) {
            return null;
        }
        try {
            final String path = apkAssets[cookie - 1].getAssetPath();
            return path.isEmpty() ? null : path;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The image file gate of {@code wrapper} when the files of {@code value}'s APK must not be decoded in this
     * process, else null. Always null for resources without a gate, which load exactly as before.
     */
    @Nullable
    static ImageFileGate gateFor(@NonNull Resources wrapper, @NonNull AssetManager assets,
            @NonNull TypedValue value) {
        final ImageFileGate gate = wrapper.getImageFileGate();
        if (gate == null || gate.decodesHere(apkPathForCookie(assets, value.assetCookie))) {
            return null;
        }
        return gate;
    }
}
