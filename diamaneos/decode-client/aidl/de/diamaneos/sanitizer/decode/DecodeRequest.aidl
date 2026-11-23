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
     * IDecodeService.MAX_BUFFERED_BYTES into memory (MAX_PROBE_BYTES for a probe), and hands the bytes to the VM
     * unopened. A larger regular file not on FUSE, up to MAX_INPUT_BYTES, is streamed to the VM instead. It never
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

    /**
     * The Android user whose image this is: answers are kept per user. The system server and SystemUI set it to the
     * user of the app they ask for, from that app's uid: without it they are refused. Any other caller may leave it
     * IDecodeService.NO_USER (its own user is taken) or name its own user. A launcher may also name a profile of its
     * user (a work profile's widget).
     */
    int ownerUser = -10000;

    /**
     * Only from the system server: the uid of the app it decodes for (its decode gate for that app, a thumbnail that
     * process loads), or -1 for a decode of its own. The service decides from it, as from every caller's own uid,
     * whether the image is per-user content (decoded in a per-user process on Android: the VM holds per-user content
     * of one user at a time) or shared (decoded for every user in one process on Android: SystemUI's, the system
     * server's own). Ignored from any other caller.
     */
    int forUid = -1;

    /** Requested checked raw pixel storage for thumbnail and region operations. */
    int pixelFormat = 0;

    /** Decode only the checked Ultra HDR gain pixels for the requested stored-base geometry. */
    boolean gainmapOnly = false;
}
