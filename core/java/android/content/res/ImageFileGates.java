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
public final class ImageFileGates {
    // The gate of the whole process (the clean icon cache, CleanCopyGate), for Resources objects without their own.
    private static volatile ImageFileGate sProcessDefault;

    // Drawables of gated APKs a gate made wholly of exact copies, by Resources implementation and gate (weakly):
    // never shared with plain loads or other gates.
    private static final java.util.WeakHashMap<ResourcesImpl, java.util.WeakHashMap<ImageFileGate, DrawableCache>>
            sGatedCaches = new java.util.WeakHashMap<>();

    private ImageFileGates() {
    }

    /** The drawable cache of {@code gate}'s loads in {@code impl}. */
    @NonNull
    static DrawableCache cacheFor(@NonNull ResourcesImpl impl, @NonNull ImageFileGate gate) {
        synchronized (sGatedCaches) {
            return sGatedCaches.computeIfAbsent(impl, i -> new java.util.WeakHashMap<>())
                    .computeIfAbsent(gate, g -> new DrawableCache());
        }
    }

    /** {@code impl}'s configuration changed: its gated caches drop what the change touches. */
    static void onConfigurationChange(@NonNull ResourcesImpl impl, int configChanges) {
        synchronized (sGatedCaches) {
            final java.util.WeakHashMap<ImageFileGate, DrawableCache> caches = sGatedCaches.get(impl);
            if (caches != null) {
                for (DrawableCache cache : caches.values()) {
                    cache.onConfigurationChange(configChanges);
                }
            }
        }
    }

    /** {@code impl} dropped its caches: its gated caches go too. */
    static void clear(@NonNull ResourcesImpl impl) {
        synchronized (sGatedCaches) {
            sGatedCaches.remove(impl);
        }
    }

    /**
     * Sets the gate every Resources object of this process uses when it has none of its own: image files of APKs it
     * does not decode here then come from clean copies. Once, at process start.
     */
    public static void setProcessDefault(@Nullable ImageFileGate gate) {
        sProcessDefault = gate;
    }

    /** {@code own} (a Resources object's gate), else the process's, else null. */
    @Nullable
    static ImageFileGate effective(@Nullable ImageFileGate own) {
        return own != null ? own : sProcessDefault;
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
     * The image file gate of {@code wrapper} (or of the process) when the files of {@code value}'s APK must not be
     * decoded in this process, else null. Always null without a gate, where they load exactly as before.
     */
    @Nullable
    static ImageFileGate gateFor(@NonNull Resources wrapper, @NonNull AssetManager assets,
            @NonNull TypedValue value) {
        final ImageFileGate gate = effective(wrapper.getImageFileGate());
        if (gate == null || gate.decodesHere(apkPathForCookie(assets, value.assetCookie))) {
            return null;
        }
        return gate;
    }
}
