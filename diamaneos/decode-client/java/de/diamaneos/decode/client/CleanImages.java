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

import android.content.Context;

import com.android.internal.diamaneos.cleancopies.CleanCopyClient;
import com.android.internal.diamaneos.cleancopies.CleanImageFile;

import java.util.ArrayList;
import java.util.List;

/**
 * The clean copies the system server keeps ({@link CleanImageStore}), for image gates in other system processes
 * (SystemUI): a copy is read through the system server's provider ({@link CleanCopyClient}, checked, no decoder runs),
 * and a file a gate misses is asked for, so the system server makes it in the VM. Never on the main thread.
 */
public final class CleanImages implements CleanCopies {
    private static CleanImages sInstance;
    // Per thread: the keys missed since trackMisses(), or null when not tracking.
    private static final ThreadLocal<ArrayList<String>> sTracked = new ThreadLocal<>();

    private final Context mContext;

    private CleanImages(Context context) {
        mContext = context;
    }

    /** The clean copies of this process. */
    public static synchronized CleanImages get(Context context) {
        if (sInstance == null) {
            sInstance = new CleanImages(
                    context.getApplicationContext() != null ? context.getApplicationContext() : context);
        }
        return sInstance;
    }

    @Override
    public Entry load(String key) {
        final CleanImageFile image = CleanCopyClient.read(mContext, key);
        return image == null ? null : CleanImageStore.entryOf(image);
    }

    @Override
    public Entry fallback(String apkPath, String file, int density, int maxWidth, int maxHeight,
            String resourceName, int userId) {
        final CleanImageFile image = CleanCopyClient.readFallback(mContext, apkPath, file, density, maxWidth,
                maxHeight, resourceName, userId);
        return image == null ? null : CleanImageStore.entryOf(image);
    }

    @Override
    public void missed(String key, String apkPath, String file, int density, int maxWidth, int maxHeight,
            String resourceName, int userId) {
        final ArrayList<String> tracked = sTracked.get();
        if (key != null && tracked != null && tracked.size() < 256 && !tracked.contains(key)) {
            tracked.add(key);
        }
        CleanCopyClient.request(mContext, key, apkPath, file, density, maxWidth, maxHeight, resourceName, userId);
    }

    /** Starts collecting, on this thread, the keys of the files gates miss. */
    public static void trackMisses() {
        sTracked.set(new ArrayList<>());
    }

    /** The keys missed on this thread since {@link #trackMisses}, and stops collecting. */
    public static List<String> trackedMisses() {
        final ArrayList<String> tracked = sTracked.get();
        sTracked.remove();
        return tracked != null ? tracked : new ArrayList<>();
    }

    /** How many of {@code keys} have no copy yet and were not refused by the VM: files that may still be made. */
    public int open(List<String> keys) {
        return CleanCopyClient.open(mContext, keys);
    }

    /** Removes the copies of the APK at {@code apkPath}, for tests that want them made again. */
    public void forget(String apkPath) {
        CleanCopyClient.forget(mContext, apkPath);
    }
}
