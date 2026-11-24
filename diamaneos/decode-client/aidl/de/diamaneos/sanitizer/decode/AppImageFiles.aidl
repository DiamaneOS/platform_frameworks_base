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

package de.diamaneos.sanitizer.decode;

/**
 * The image files an app's splash screens and launcher icons name (IDecodeService.listAppImageFiles): for each, the
 * APK it is in, the file in that APK, the density its resource value has as a load at the asked density sees it
 * (android.util.TypedValue.density, rewritten for a load at another density as android.content.res.Resources does)
 * and the resource it is (Resources.getResourceName). Only names: the caller reads each file from the APK and has it
 * decoded with decodeThumbnail, checked as any other image.
 */
parcelable AppImageFiles {
    /** IDecodeService.STATUS_*. The arrays are only meaningful with STATUS_OK. */
    int status;

    /** Same length, at most IDecodeService.MAX_APP_IMAGE_FILES. */
    String[] apkPaths;
    String[] files;
    int[] densities;
    String[] resources;
}
