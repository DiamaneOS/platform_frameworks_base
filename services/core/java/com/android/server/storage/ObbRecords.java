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

package com.android.server.storage;

import android.annotation.Nullable;
import android.util.ArrayMap;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * DiamaneOS: the OBB images the system server mounted. Each is recorded once, by its owner (the app's full uid, so its
 * Android user too) and its canonical path, and by vold's volume ID. Queries and unmounts find only the caller's own
 * images, so an app of another user, or another app, learns nothing of them. Each record is removed once, by whichever
 * end comes first: the app's unmount, its death, the volume's end in vold (onVolumeDestroyed) or its card going.
 *
 * <p>Not thread-safe: StorageManagerService holds its OBB lock around every call.
 *
 * @param <S> StorageManagerService's OBB state
 */
public final class ObbRecords<S extends ObbRecords.Record> {
    /** What a record needs of StorageManagerService's OBB state. */
    public interface Record {
        /** The uid of the app that mounted it. */
        int ownerUid();

        /** The canonical path of the image, the one vold opens. */
        String canonicalPath();

        /** vold's volume ID, or null before vold made the volume. */
        @Nullable
        String volId();
    }

    private final ArrayMap<String, S> mByOwner = new ArrayMap<>();

    private static String key(int ownerUid, String canonicalPath) {
        return ownerUid + "\0" + canonicalPath;
    }

    /** The canonical form of a path an app names, or null when it cannot be resolved. */
    @Nullable
    public static String canonical(String rawPath) {
        try {
            return new File(rawPath).getCanonicalPath();
        } catch (IOException e) {
            return null;
        }
    }

    /** The image the caller mounted at this canonical path, or null. */
    @Nullable
    public S find(int callingUid, @Nullable String canonicalPath) {
        return canonicalPath == null ? null : mByOwner.get(key(callingUid, canonicalPath));
    }

    /** The image vold serves as this volume, or null. */
    @Nullable
    public S findByVolId(@Nullable String volId) {
        if (volId == null) return null;
        for (int i = 0; i < mByOwner.size(); i++) {
            if (volId.equals(mByOwner.valueAt(i).volId())) return mByOwner.valueAt(i);
        }
        return null;
    }

    /** Records a mounted image. False when its owner has it mounted already. */
    public boolean add(S state) {
        final String k = key(state.ownerUid(), state.canonicalPath());
        if (mByOwner.containsKey(k)) return false;
        mByOwner.put(k, state);
        return true;
    }

    /** Removes the record, if it is still there. True only for the call that removed it. */
    public boolean remove(S state) {
        final String k = key(state.ownerUid(), state.canonicalPath());
        if (mByOwner.get(k) != state) return false;
        mByOwner.remove(k);
        return true;
    }

    /** The records of images below a path (a volume that goes). */
    public List<S> below(String path) {
        final List<S> found = new ArrayList<>();
        for (int i = 0; i < mByOwner.size(); i++) {
            String image = mByOwner.valueAt(i).canonicalPath();
            String root = path.endsWith("/") ? path : path + "/";
            if (image.startsWith(root)) found.add(mByOwner.valueAt(i));
        }
        return found;
    }
}
