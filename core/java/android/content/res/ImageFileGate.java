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
import android.graphics.drawable.Drawable;
import android.util.TypedValue;

import java.io.InputStream;

/**
 * Decides how the image files of one {@link Resources} object become pixels. SystemUI sets one on the resources it
 * inflates another app's notification layouts with, so that bitmap files (PNG, JPEG, WebP, GIF and so on) from an APK
 * outside the system image are decoded in a protected VM and never in this process. Every other Resources object has
 * none and loads exactly as before.
 *
 * <p>Only files are affected. XML (layouts, vectors, selectors, layer lists) is parsed as usual, and the bitmap files
 * an XML drawable names go through the gate again.
 *
 * @hide
 */
public interface ImageFileGate {
    /**
     * True when image files of the APK at {@code apkPath} may be decoded here: the verified system image. False for
     * an app, an updated system app or a path that is not known ({@code null}).
     */
    boolean decodesHere(@Nullable String apkPath);

    /**
     * The drawable for the image file {@code value.string} of the APK at {@code apkPath}, made without decoding the
     * file in this process. Never null: an empty drawable when it cannot be made. The result is not cached by
     * {@link Resources}.
     */
    @NonNull
    Drawable loadImage(@NonNull Resources res, @NonNull TypedValue value, @Nullable String apkPath);

    /**
     * The bytes to hand out instead of the raw file {@code value.string} of the APK at {@code apkPath}, for callers
     * that decode the stream themselves ({@code <bitmap>}, {@code <nine-patch>}, {@code ImageDecoder}): an image
     * written by this process from pixels decoded elsewhere, or an empty image. Takes ownership of
     * {@code original}.
     */
    @NonNull
    InputStream openImage(@NonNull Resources res, @NonNull TypedValue value, @Nullable String apkPath,
            @NonNull InputStream original);

    /**
     * An XML drawable of such an APK failed to load, for example because an image inside it could not be made: the
     * drawable to show instead, so that the rest of the layout still shows.
     */
    @NonNull
    Drawable onXmlDrawableFailed(@NonNull Resources res, @NonNull TypedValue value, @NonNull Exception e);

    /**
     * An XML drawable {@code id} of such an APK was made: the drawable to hand out. A gate whose images fill in
     * later may wrap it, so that it loads again once an image file inside it ({@code <bitmap>}, {@code <nine-patch>})
     * is made. By default the drawable itself.
     */
    @NonNull
    default Drawable onXmlDrawableLoaded(@NonNull Resources res, @NonNull TypedValue value, int id, int density,
            @NonNull Drawable drawable) {
        return drawable;
    }

    /**
     * Resources of {@code packageName} behind this same gate, for a resource Icon of that package loaded with a
     * context whose resources have this gate (see {@code Icon}). Null when they cannot be made: the icon is then not
     * loaded, never loaded with plain resources.
     */
    @Nullable
    Resources resourcesFor(@NonNull String packageName);
}
