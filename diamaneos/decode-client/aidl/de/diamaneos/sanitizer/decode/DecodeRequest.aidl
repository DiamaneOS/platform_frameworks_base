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
 * One file to decode. The service never trusts any field for anything but routing and limits.
 */
parcelable DecodeRequest {
    /**
     * The bytes, opened read-only by the caller. A regular file, a memory file or a pipe, possibly from another app.
     * The service reads it under its own deadline (a pipe that stalls or never ends cannot hold anyone up), at most
     * IDecodeService.MAX_INPUT_BYTES (MAX_PROBE_BYTES for a probe), and hands the bytes to the VM unopened. It never
     * parses them itself.
     */
    ParcelFileDescriptor file;

    /**
     * The package that supplied the bytes (MediaProvider's owner package of the file), for the fair queue. Empty when
     * unknown. All of an app's processes share one lane.
     */
    String ownerPackage;

    /** IDecodeService.PRIORITY_USER_WAITING or IDecodeService.PRIORITY_BACKGROUND. */
    int priority;

    /** The caller's MIME type for the file. A hint for logs only: the VM detects the format from the bytes. */
    String mimeTypeHint;
}
