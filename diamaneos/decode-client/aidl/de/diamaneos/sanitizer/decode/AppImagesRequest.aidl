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
 * Which images of an app to list for the clean icon cache (IDecodeService.listAppImageFiles): its splash themes and
 * its launcher icons, in both night modes, at the given densities.
 */
parcelable AppImagesRequest {
    /** The app and the user it is installed for. */
    String packageName;
    int userId;

    /** Theme resource ids of its launcher activities, whose splash screen images are listed. */
    int[] themes;

    /** The splash screen theme the app chose at run time (a resource name), or null. */
    @nullable String splashThemeName;

    /** Icon resource ids of its launcher activities. */
    int[] icons;

    /** The densities (dpi) the icons are loaded at: 0 is the display's own, loaded without a density. */
    int[] densities;
}
