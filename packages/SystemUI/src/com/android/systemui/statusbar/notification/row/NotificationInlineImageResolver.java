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

import android.app.ActivityManager;
import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.ImageResolver;
import com.android.internal.widget.MessagingMessage;

import de.diamaneos.decode.client.ImageThreads;
import de.diamaneos.decode.client.VmDecode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Custom resolver with built-in image cache for image messages.
 *
 * If the URL points to a bitmap that's larger than the maximum width or height, the bitmap
 * will be resized down to that maximum size before being cached. See {@link #getMaxImageWidth()},
 * {@link #getMaxImageHeight()}, and {@link #resolveImage(Uri)} for the downscaling implementation.
 */
public class NotificationInlineImageResolver implements ImageResolver {
    private static final String TAG = NotificationInlineImageResolver.class.getSimpleName();
    // An image message may take this long in the VM before it is left out.
    private static final long VM_DEADLINE_MS = 2_000;

    // Timeout for loading images from ImageCache when calling from UI thread
    private static final long MAX_UI_THREAD_TIMEOUT_MS = 100L;

    private final Context mContext;
    private final ImageCache mImageCache;
    private Set<Uri> mWantedUriSet;

    // max allowed bitmap width, in pixels
    @VisibleForTesting
    protected int mMaxImageWidth;
    // max allowed bitmap height, in pixels
    @VisibleForTesting
    protected int mMaxImageHeight;
    // DiamaneOS: the posting app, which the row's images are charged to (unknown until the row has its entry).
    private volatile String mOwner = VmDecode.unknownOwner("inline");

    /** DiamaneOS: the posting app of the row: its images are charged to it, in its lane. */
    public void setOwner(String ownerPackage) {
        mOwner = ownerPackage == null || ownerPackage.isEmpty() ? VmDecode.unknownOwner("inline") : ownerPackage;
    }

    /** DiamaneOS: runs the cache's preload on an image thread of the posting app (the VM is waited for there). */
    java.util.concurrent.Executor preloadExecutor() {
        final String owner = mOwner;
        return task -> {
            // Gave way in a full image lane, or no room: run where no VM is waited for (the load comes back empty).
            if (ImageThreads.execute(owner, task, () -> android.os.AsyncTask.THREAD_POOL_EXECUTOR.execute(task))
                    == null) {
                android.os.AsyncTask.THREAD_POOL_EXECUTOR.execute(task);
            }
        };
    }

    /**
     * Constructor.
     * @param context    Context.
     * @param imageCache The implementation of internal cache.
     */
    public NotificationInlineImageResolver(Context context, ImageCache imageCache) {
        mContext = context;
        mImageCache = imageCache;

        if (mImageCache != null) {
            mImageCache.setImageResolver(this);
        }

        updateMaxImageSizes();
    }

    @VisibleForTesting
    public Context getContext() {
        return mContext;
    }

    /**
     * Check if this resolver has its internal cache implementation.
     * @return True if has its internal cache, false otherwise.
     */
    public boolean hasCache() {
        return mImageCache != null && !isLowRam();
    }

    private boolean isLowRam() {
        return ActivityManager.isLowRamDeviceStatic();
    }

    /**
     * Update the maximum width and height allowed for bitmaps, ex. after a configuration change.
     */
    public void updateMaxImageSizes() {
        mMaxImageWidth = getMaxImageWidth();
        mMaxImageHeight = getMaxImageHeight();
    }

    @VisibleForTesting
    protected int getMaxImageWidth() {
        return mContext.getResources().getDimensionPixelSize(
                R.dimen.notification_custom_view_max_image_width);
    }

    @VisibleForTesting
    protected int getMaxImageHeight() {
        return mContext.getResources().getDimensionPixelSize(
                R.dimen.messaging_image_max_height);
    }

    /**
     * To resolve image from specified uri directly. If the resulting image is larger than the
     * maximum allowed size, scale it down.
     * @param uri Uri of the image.
     * @return Drawable of the image, or null if unable to load.
     */
    Drawable resolveImage(Uri uri) {
        // Images from apps are decoded in the protected VM, never in SystemUI. A failure leaves the message without
        // its image.
        if (!ImageThreads.isImageThread()) {
            // The VM is waited for only on the image threads (the cache's preload runs there, charged to the app).
            return null;
        }
        try {
            // Charged to the posting app (setOwner), in its own lane.
            final Bitmap bitmap = VmDecode.decodeUri(mContext, mContext.getContentResolver(), uri, mOwner,
                    VmDecode.PRIORITY_USER_WAITING, Math.max(1, mMaxImageWidth),
                    Math.max(1, mMaxImageHeight), VmDecode.deadlineIn(VM_DEADLINE_MS));
            return new BitmapDrawable(mContext.getResources(), bitmap);
        } catch (VmDecode.UnavailableException | VmDecode.RejectedException ex) {
            Log.d(TAG, "resolveImage: not decoded in the VM: " + ex.getMessage());
        } catch (Exception ex) {
            // Catch general Exception because ContentResolver can re-throw arbitrary Exception
            // from remote process as a RuntimeException. See: Parcel#readException
            Log.d(TAG, "resolveImage: Can't load image from " + uri, ex);
        }
        return null;
    }

    /**
     * Loads an image from the Uri.
     * This method is synchronous and is usually called from the Main thread.
     * It will time-out after MAX_UI_THREAD_TIMEOUT_MS.
     *
     * @param uri Uri of the target image.
     * @return drawable of the image, null if loading failed/timeout
     */
    @Override
    public Drawable loadImage(Uri uri) {
        return hasCache() ? loadImageFromCache(uri, MAX_UI_THREAD_TIMEOUT_MS) : resolveImage(uri);
    }

    private Drawable loadImageFromCache(Uri uri, long timeoutMs) {
        // if the uri isn't currently cached, try caching it first
        if (!mImageCache.hasEntry(uri)) {
            mImageCache.preload((uri));
        }
        return mImageCache.get(uri, timeoutMs);
    }

    /**
     * Resolve the message list from specified notification and
     * refresh internal cache according to the result.
     * @param notification The Notification to be resolved.
     */
    public void preloadImages(Notification notification) {
        if (!hasCache()) {
            return;
        }

        retrieveWantedUriSet(notification);
        Set<Uri> wantedSet = getWantedUriSet();
        wantedSet.forEach(uri -> {
            if (!mImageCache.hasEntry(uri)) {
                // The uri is not in the cache, we need trigger a loading task for it.
                mImageCache.preload(uri);
            }
        });
    }

    /**
     * Try to purge unnecessary cache entries.
     */
    public void purgeCache() {
        if (!hasCache()) {
            return;
        }
        mImageCache.purge();
    }

    private void retrieveWantedUriSet(Notification notification) {
        Parcelable[] messages;
        Parcelable[] historicMessages;
        List<Notification.MessagingStyle.Message> messageList;
        List<Notification.MessagingStyle.Message> historicList;
        Set<Uri> result = new HashSet<>();

        Bundle extras = notification.extras;
        if (extras == null) {
            return;
        }

        messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        messageList = messages == null ? null :
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages);
        if (messageList != null) {
            for (Notification.MessagingStyle.Message message : messageList) {
                if (MessagingMessage.hasImage(message)) {
                    result.add(message.getDataUri());
                }
            }
        }

        historicMessages = extras.getParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES);
        historicList = historicMessages == null ? null :
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(historicMessages);
        if (historicList != null) {
            for (Notification.MessagingStyle.Message historic : historicList) {
                if (MessagingMessage.hasImage(historic)) {
                    result.add(historic.getDataUri());
                }
            }
        }

        mWantedUriSet = result;
    }

    Set<Uri> getWantedUriSet() {
        return mWantedUriSet;
    }

    /**
     * Wait for a maximum timeout for images to finish preloading
     * @param timeoutMs total timeout time
     */
    void waitForPreloadedImages(long timeoutMs) {
        if (!hasCache()) {
            return;
        }
        Set<Uri> preloadedUris = getWantedUriSet();
        if (preloadedUris != null) {
            // Decrement remaining timeout after each image check
            long endTimeMs = SystemClock.elapsedRealtime() + timeoutMs;
            preloadedUris.forEach(
                    uri -> loadImageFromCache(uri, endTimeMs - SystemClock.elapsedRealtime()));
        }
    }

    void cancelRunningTasks() {
        if (!hasCache()) {
            return;
        }
        mImageCache.cancelRunningTasks();
    }

    /**
     * A interface for internal cache implementation of this resolver.
     */
    interface ImageCache {
        /**
         * Load the image from cache first then resolve from uri if missed the cache.
         * @param uri The uri of the image.
         * @return Drawable of the image.
         */
        Drawable get(Uri uri, long timeoutMs);

        /**
         * Set the image resolver that actually resolves image from specified uri.
         * @param resolver The resolver implementation that resolves image from specified uri.
         */
        void setImageResolver(NotificationInlineImageResolver resolver);

        /**
         * Check if the uri is in the cache no matter it is loading or loaded.
         * @param uri The uri to check.
         * @return True if it is already in the cache; false otherwise.
         */
        boolean hasEntry(Uri uri);

        /**
         * Start a new loading task for the target uri.
         * @param uri The target to load.
         */
        void preload(Uri uri);

        /**
         * Purge unnecessary entries in the cache.
         */
        void purge();

        /**
         * Cancel all unfinished image loading tasks
         */
        void cancelRunningTasks();
    }

}
