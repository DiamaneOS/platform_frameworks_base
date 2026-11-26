/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row;

import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Log;
import de.diamaneos.decode.client.VmDecode;

import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntSupplier;

/**
 * A cache for inline images of image messages.
 */
public class NotificationInlineImageCache implements NotificationInlineImageResolver.ImageCache {
    private static final String TAG = NotificationInlineImageCache.class.getSimpleName();

    private NotificationInlineImageResolver mResolver;
    private final ConcurrentHashMap<Uri, PreloadImageTask> mCache;

    public NotificationInlineImageCache() {
        mCache = new ConcurrentHashMap<>();
    }

    @Override
    public void setImageResolver(NotificationInlineImageResolver resolver) {
        mResolver = resolver;
    }

    @Override
    public boolean hasEntry(Uri uri) {
        // DiamaneOS: a load that ended without its image for a reason that passes is loaded again.
        final PreloadImageTask task = mCache.get(uri);
        return task != null && !task.endedForNow();
    }

    @Override
    public void preload(Uri uri) {
        // DiamaneOS: a rebind for another image cannot retry an unchanged blocked lane.
        final PreloadImageTask newTask;
        final PreloadImageTask old;
        synchronized (mCache) {
            final PreloadImageTask existing = mCache.get(uri);
            if (existing != null && existing.capacityBlocked()) return;
            newTask = new PreloadImageTask(mResolver, uri);
            old = mCache.put(uri, newTask);
        }
        // DiamaneOS: wait for the VM on an image thread of the posting app.
        if (old != null) old.cancel(true);
        newTask.executeOnExecutor(mResolver.preloadExecutor(), uri);
    }

    @Override
    public Drawable get(Uri uri, long timeoutMs) {
        Drawable result = null;
        try {
            result = mCache.get(uri).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException
                | TimeoutException | CancellationException ex) {
            Log.d(TAG, "get: Failed get image from " + uri + " " + ex);
        }
        return result;
    }

    @Override
    public void purge() {
        Set<Uri> wantedSet = mResolver.getWantedUriSet();
        synchronized (mCache) {
            mCache.entrySet().removeIf(entry -> !wantedSet.contains(entry.getKey()));
            mResolver.outcomes().retain(wantedSet); // DiamaneOS
        }
    }

    @Override
    public void cancelRunningTasks() {
        mCache.forEach((key, value) -> {
            if (value.getStatus() != AsyncTask.Status.FINISHED) {
                value.cancel(true);
            }
        });
    }

    private class PreloadImageTask extends AsyncTask<Uri, Void, Drawable> {
        private final NotificationInlineImageResolver mResolver;
        private final Uri mUri; // DiamaneOS
        // DiamaneOS: ended without its image for a reason that passes (not refused by the VM).
        private volatile boolean mEndedForNow;
        private boolean mRefused;
        private IntSupplier mCapacity;
        private int mCapacitySeen;

        PreloadImageTask(NotificationInlineImageResolver resolver, Uri uri) {
            mResolver = resolver;
            mUri = uri;
        }

        @Override
        protected Drawable doInBackground(Uri... uris) {
            Uri target = uris[0];
            final Drawable image = mResolver.resolveImage(target, () -> mRefused = true);
            // DiamaneOS: publish before the completion flag and before the image task clears its lanes.
            mCapacity = VmDecode.capacityWakes();
            mCapacitySeen = mCapacity != null ? mCapacity.getAsInt() : 0;
            mEndedForNow = image == null && !mRefused && !mResolver.outcomes().wasRefused(target);
            return image;
        }

        // DiamaneOS: a cancelled load, or one that ended for now, is no entry.
        boolean endedForNow() {
            return isCancelled() || (mEndedForNow && !capacityBlocked());
        }

        boolean capacityBlocked() {
            return !isCancelled() && mEndedForNow && mCapacity != null && mCapacity.getAsInt() == mCapacitySeen;
        }

        // DiamaneOS: the row is inflated again for an image that can still come.
        @Override
        protected void onPostExecute(Drawable image) {
            ended(image != null);
        }

        @Override
        protected void onCancelled(Drawable image) {
            ended(false);
        }

        private void ended(boolean made) {
            synchronized (mCache) {
                if (mCache.get(mUri) != this) return;
                final Set<Uri> wanted = mResolver.getWantedUriSet();
                if (mRefused && !isCancelled()) mResolver.outcomes().refused(mUri);
                mResolver.outcomes().ended(mUri, made, wanted != null && wanted.contains(mUri),
                        mCapacity, mCapacitySeen);
            }
        }
    }
}
