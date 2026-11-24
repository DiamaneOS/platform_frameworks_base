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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.LruCache;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Icons that point at compressed image bytes chosen by another app (a URI, or bytes from createWithData) are decoded
 * in the VM. Bitmap and resource icons are passed through unchanged. A VM icon that cannot be decoded gives null and
 * the caller shows its placeholder: never a decode in this process.
 */
public final class VmIcons {
    private static final String TAG = "VmDecode";

    // Decoded icons by source and box, so a notification that updates often does not decode the same image again.
    private static final LruCache<String, Bitmap> sCache = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return (int) Math.min(Integer.MAX_VALUE, pixels(value) * 4);
        }
    };

    private VmIcons() {
    }

    /**
     * What {@code bitmap} holds, in pixels of 4 bytes, for the caps and the cache: its own pixels and those of an
     * Ultra HDR gain map it carries.
     */
    static long pixels(Bitmap bitmap) {
        long pixels = (long) bitmap.getWidth() * bitmap.getHeight();
        if (bitmap.hasGainmap() && bitmap.getGainmap() != null) {
            final Bitmap gain = bitmap.getGainmap().getGainmapContents();
            if (gain != null) {
                pixels += (gain.getAllocationByteCount() + 3L) / 4;
            }
        }
        return pixels;
    }

    /** Empties the decoded icon cache (memory is low). */
    static void trim() {
        sCache.evictAll();
    }

    static Bitmap cacheGet(String key) {
        final Bitmap bitmap = sCache.get(key);
        if (bitmap != null && VmDecode.madeLogging()) {
            VmDecode.madeLine("kept " + bitmap.getWidth() + "x" + bitmap.getHeight() + " made in the VM before");
        }
        return bitmap;
    }

    private static final ThreadLocal<Boolean> sLocalWrites = new ThreadLocal<>();

    static void localCacheWrites(boolean local) {
        if (local) sLocalWrites.set(true);
        else sLocalWrites.remove();
    }

    static boolean sharedCacheWrites() {
        return sLocalWrites.get() == null;
    }

    static void cachePut(String key, Bitmap bitmap) {
        // One image may take at most a quarter of the cache, so it cannot flush the others.
        if (pixels(bitmap) * 4 <= MAX_CACHE_ENTRY_BYTES) {
            sCache.put(key, bitmap);
        } else {
            sNotKept.get()[0]++;
        }
    }

    // Per thread: images made since beginKeptTracking that were too big for the cache.
    private static final ThreadLocal<int[]> sNotKept = ThreadLocal.withInitial(() -> new int[1]);

    /** Starts counting, on this thread, images made but too big to keep in the cache. */
    static void beginKeptTracking() {
        sNotKept.get()[0] = 0;
    }

    /**
     * True when an image made on this thread since {@link #beginKeptTracking} was too big for the cache: whoever asks
     * again does not find it, so nobody is told to ask again (that would make it again and again).
     */
    static boolean notKept() {
        return sNotKept.get()[0] > 0;
    }

    private static final int MAX_CACHE_ENTRY_BYTES = 4 * 1024 * 1024;

    /**
     * {@code {maxWidth, maxHeight}} made smaller, keeping its shape, so that an image decoded into it fits a cache
     * entry (ARGB): what is made can be kept and shown, never made again and again.
     */
    static int[] cacheableBox(int maxWidth, int maxHeight) {
        final long maxPixels = MAX_CACHE_ENTRY_BYTES / 4;
        final long pixels = (long) maxWidth * maxHeight;
        if (pixels <= maxPixels) {
            return new int[] {maxWidth, maxHeight};
        }
        final double scale = Math.sqrt((double) maxPixels / pixels);
        return new int[] {Math.max(1, (int) Math.floor(maxWidth * scale)),
                Math.max(1, (int) Math.floor(maxHeight * scale))};
    }
    private static final long URI_CACHE_MS = 30_000;

    /** {@code bitmap}, or an immutable copy: cached bitmaps are shared between views, nobody may draw into them. */
    static Bitmap immutable(Bitmap bitmap) {
        if (!bitmap.isMutable()) {
            return bitmap;
        }
        final Bitmap copy = bitmap.copy(bitmap.getConfig(), false);
        if (copy == null) {
            return bitmap;
        }
        // The copy keeps the colour space. The Ultra HDR gain map goes with it too.
        if (bitmap.hasGainmap() && !copy.hasGainmap()) {
            copy.setGainmap(bitmap.getGainmap());
        }
        bitmap.recycle();
        return copy;
    }

    private static volatile Bitmap sEmpty;

    /** A new empty (one transparent pixel) bitmap icon: what an image that could not be made is replaced by. */
    public static Icon emptyIcon() {
        Bitmap empty = sEmpty;
        if (empty == null) {
            empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, false);
            sEmpty = empty;
        }
        return Icon.createWithBitmap(empty);
    }

    /** True for icons whose image bytes come from another app in compressed form. */
    public static boolean needsVm(Icon icon) {
        if (icon == null) {
            return false;
        }
        final int type = icon.getType();
        return type == Icon.TYPE_URI || type == Icon.TYPE_URI_ADAPTIVE_BITMAP || type == Icon.TYPE_DATA;
    }

    /**
     * The total time a UI image may take in the VM (an icon, a person, a preview). A VM that hangs then costs a
     * placeholder, not a stuck notification.
     */
    public static final long UI_DEADLINE_MS = 2_000;

    /**
     * Like {@link Icon#loadDrawable}, but a VM icon is decoded in the VM to fit {@code maxPx} x {@code maxPx}, by
     * {@code deadline} ({@link VmDecode#deadlineIn}). Null when it cannot be decoded, also on the main thread.
     * {@code ownerPackage} is the app that supplied the icon, empty when unknown.
     */
    public static Drawable loadDrawable(Icon icon, Context context, String ownerPackage, int maxPx,
            long deadline) {
        if (icon == null) {
            return null;
        }
        if (!needsVm(icon)) {
            return icon.loadDrawable(context);
        }
        final Bitmap bitmap = decode(icon, context, ownerPackage, maxPx, deadline);
        if (bitmap == null) {
            return null;
        }
        final BitmapDrawable bitmapDrawable = new BitmapDrawable(context.getResources(), bitmap);
        final Drawable result = icon.getType() == Icon.TYPE_URI_ADAPTIVE_BITMAP
                ? new AdaptiveIconDrawable(null, bitmapDrawable) : bitmapDrawable;
        if (icon.hasTint()) {
            result.mutate();
            result.setTintList(icon.getTintList());
            result.setTintBlendMode(icon.getTintBlendMode());
        }
        return result;
    }

    /**
     * Like {@link #loadDrawable(Icon, Context, String, int, long)}, but never waits for the VM: a VM icon comes from
     * what was decoded before, or is null. For shared threads that must not wait (the notification inflation thread).
     */
    public static Drawable loadDrawableNoWait(Icon icon, Context context, String ownerPackage, int maxPx) {
        if (icon == null) {
            return null;
        }
        if (!needsVm(icon)) {
            return icon.loadDrawable(context);
        }
        final Icon cached = cachedSafeIcon(icon, context, ownerPackage, maxPx, maxPx);
        return cached == null ? null : cached.loadDrawable(context);
    }

    /** {@link #loadDrawable(Icon, Context, String, int, long)} within {@link #UI_DEADLINE_MS} from now. */
    public static Drawable loadDrawable(Icon icon, Context context, String ownerPackage, int maxPx) {
        return loadDrawable(icon, context, ownerPackage, maxPx, VmDecode.deadlineIn(UI_DEADLINE_MS));
    }

    /**
     * A bitmap icon with the same picture and tint, decoded in the VM by {@code deadline}, for a VM icon. Other
     * icons come back as they are. Null when a VM icon cannot be decoded.
     */
    public static Icon toSafeIcon(Icon icon, Context context, String ownerPackage, int maxPx, long deadline) {
        return toSafeIcon(icon, context, ownerPackage, maxPx, maxPx, deadline);
    }

    /** {@link #toSafeIcon(Icon, Context, String, int, long)} with a box instead of a square. */
    public static Icon toSafeIcon(Icon icon, Context context, String ownerPackage, int maxWidth, int maxHeight,
            long deadline) {
        if (!needsVm(icon)) {
            return icon;
        }
        final Bitmap bitmap = decode(icon, context, ownerPackage, maxWidth, maxHeight, deadline);
        if (bitmap == null) {
            return null;
        }
        return iconLike(icon, bitmap, icon.getType() == Icon.TYPE_URI_ADAPTIVE_BITMAP);
    }

    /**
     * For views that load icons on the main thread (status bar, shelf, always-on display, bubbles): a VM icon is
     * replaced by {@code placeholder}, never decoded. Other icons are passed through.
     */
    public static Icon withoutVmIcon(Icon icon, Icon placeholder) {
        return needsVm(icon) ? placeholder : icon;
    }

    /**
     * A context of {@code userId} made from {@code context}, for the calls of this class: an icon is the image of
     * the user of the context it is decoded with (VmDecode OWNER_USER). Null for no known user (below 0) or when it
     * cannot be made: then the icon is not decoded.
     */
    public static Context userContext(Context context, int userId) {
        if (userId < 0) {
            return null;
        }
        if (context.getUserId() == userId) {
            return context;
        }
        try {
            return context.createContextAsUser(android.os.UserHandle.of(userId), 0);
        } catch (RuntimeException e) {
            Log.w(TAG, "no context of user " + userId + ": " + e);
            return null;
        }
    }

    /**
     * The VM-decoded bitmap of a VM icon by {@code deadline}, or null. Failures are logged, not thrown. As everywhere
     * in this class, {@code context} is a context of the user whose icon it is (the posting app's user): the icon is
     * that user's image (VmDecode OWNER_USER), and a URI icon is opened through that user's content resolver.
     */
    public static Bitmap decode(Icon icon, Context context, String ownerPackage, int maxPx, long deadline) {
        return decode(icon, context, ownerPackage, maxPx, maxPx, deadline);
    }

    /** {@link #decode(Icon, Context, String, int, long)} with a box instead of a square. */
    public static Bitmap decode(Icon icon, Context context, String ownerPackage, int maxWidth, int maxHeight,
            long deadline) {
        if (!needsVm(icon) || maxWidth < 1 || maxHeight < 1) {
            return null;
        }
        if (icon.getType() != Icon.TYPE_DATA && String.valueOf(icon.getUri()).length() > MAX_URI_CHARS) {
            Log.w(TAG, "icon URI too long, not opened");
            return null;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "icon decode on the main thread refused, placeholder shown");
            return null;
        }
        final String key = cacheKey(icon, context, ownerPackage, maxWidth + "x" + maxHeight);
        if (key != null) {
            final Bitmap cached = cacheGet(key);
            if (cached != null) {
                return cached;
            }
        }
        if (deadline != VmDecode.NO_DEADLINE && deadline <= SystemClock.uptimeMillis()) {
            // No time left: what is cached only. Nothing is opened or handed to the VM.
            return null;
        }
        // Decoded at most as big as a cache entry holds, under the key of the box asked for.
        final int[] box = cacheableBox(maxWidth, maxHeight);
        try {
            Bitmap bitmap;
            if (icon.getType() == Icon.TYPE_DATA) {
                bitmap = VmDecode.decodeBytes(context, icon.getDataBytes(), icon.getDataOffset(),
                        icon.getDataLength(), ownerPackage, context.getUserId(), VmDecode.PRIORITY_USER_WAITING,
                        box[0], box[1], deadline);
            } else {
                bitmap = VmDecode.decodeUri(context, context.getContentResolver(), icon.getUri(), ownerPackage,
                        context.getUserId(), VmDecode.PRIORITY_USER_WAITING, box[0], box[1], deadline);
            }
            // Cached bitmaps are shared between views: an immutable copy, so nobody can draw into it.
            bitmap = immutable(bitmap);
            if (sharedCacheWrites() && key != null) {
                cachePut(key, bitmap);
            }
            return bitmap;
        } catch (VmDecode.RejectedException e) {
            // Refused by the VM (broken, unsupported, hostile): not worth asking again. A URI that could not be opened
            // (not there yet, the provider not ready) is tried again later like any passing failure.
            if (key != null && !e.openFailed) {
                sRejected.put(stableKey(key), Boolean.TRUE);
            }
            Log.w(TAG, "icon not decoded, placeholder shown: " + e.getMessage());
            return null;
        } catch (VmDecode.UnavailableException e) {
            Log.w(TAG, "icon not decoded, placeholder shown: " + e.getMessage());
            return null;
        }
    }

    /**
     * {@code drawable} drawn into a new bitmap: at its own size fitted into {@code maxWidth} x {@code maxHeight}, and a
     * smaller one (a vector) drawn larger up to {@code minSide}, so it stays sharp in a bigger view. An adaptive icon
     * is drawn whole (both layers, no mask), to be shown as an adaptive bitmap. A square of {@code minSide} when the
     * drawable has no size.
     */
    static Bitmap draw(Drawable drawable, int minSide, int maxWidth, int maxHeight) {
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        if (width <= 0 || height <= 0) {
            width = minSide;
            height = minSide;
        }
        float scale = Math.min((float) maxWidth / width, (float) maxHeight / height);
        if (scale > 1f) {
            // Up to minSide on the longer side, never past the box.
            scale = Math.min(scale, Math.max(1f, (float) minSide / Math.max(width, height)));
        }
        width = Math.max(1, Math.round(width * scale));
        height = Math.max(1, Math.round(height * scale));
        final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        if (drawable instanceof AdaptiveIconDrawable) {
            final AdaptiveIconDrawable adaptive = (AdaptiveIconDrawable) drawable;
            for (Drawable layer : new Drawable[] {adaptive.getBackground(), adaptive.getForeground()}) {
                if (layer != null) {
                    layer.setBounds(0, 0, width, height);
                    layer.draw(canvas);
                }
            }
        } else {
            drawable.setBounds(0, 0, width, height);
            drawable.draw(canvas);
        }
        return immutable(bitmap);
    }

    /** A bitmap icon of {@code bitmap} like {@code original}: adaptive when it was, with its tint. */
    static Icon iconLike(Icon original, Bitmap bitmap, boolean adaptive) {
        final Icon safe = adaptive ? Icon.createWithAdaptiveBitmap(bitmap) : Icon.createWithBitmap(bitmap);
        if (original.hasTint()) {
            safe.setTintList(original.getTintList());
            safe.setTintBlendMode(original.getTintBlendMode());
        }
        return safe;
    }

    /**
     * The decode of a VM icon from the cache, as a bitmap icon like it, or null. Never asks the VM, on any thread: a
     * spent budget or quota does not take away an image that is already made.
     */
    static Icon cachedSafeIcon(Icon icon, Context context, String ownerPackage, int maxWidth, int maxHeight) {
        if (!needsVm(icon) || context == null) {
            return null;
        }
        final String key = cacheKey(icon, context, ownerPackage, maxWidth + "x" + maxHeight);
        Bitmap bitmap = key == null ? null : cacheGet(key);
        if (bitmap == null && icon.getType() != Icon.TYPE_DATA) {
            // A URI decode of the window just before is still good: no flicker when a rebuild crosses a window.
            final String previous = uriKey(icon, context, ownerPackage, maxWidth + "x" + maxHeight,
                    SystemClock.uptimeMillis() / URI_CACHE_MS - 1);
            bitmap = cacheGet(previous);
        }
        return bitmap == null ? null
                : iconLike(icon, bitmap, icon.getType() == Icon.TYPE_URI_ADAPTIVE_BITMAP);
    }

    // URI and data icons the VM refused, by stable key: not asked for again (retries skip them).
    private static final LruCache<String, Boolean> sRejected = new LruCache<>(256);

    /**
     * True when the VM refused the image behind {@code key} (a cache key or a stable key) itself (a broken or
     * unsupported file), in any window.
     */
    static boolean wasRejected(String key) {
        return key != null && sRejected.get(stableKey(key)) != null;
    }

    /**
     * {@code key} (a {@link #cacheKey}) without the time window of a URI icon: the key a refusal and the retries of
     * an image are counted by, so they do not start over with every window.
     */
    static String stableKey(String key) {
        if (key == null || !key.startsWith("uri:")) {
            return key;
        }
        // Only the window uriKey appends (":t" and digits at the very end), so a key that is stable already, whose
        // URI may hold ":t" itself, comes back as it is.
        final int window = key.lastIndexOf(":t");
        if (window <= 0 || window + 2 >= key.length()) {
            return key;
        }
        for (int i = window + 2; i < key.length(); i++) {
            if (!Character.isDigit(key.charAt(i))) {
                return key;
            }
        }
        return key.substring(0, window) + ":stable";
    }

    // URI icons by user, owner and URI (as SystemUI's own image caches do), data icons by user and a hash of their
    // bytes: the VM made the image as one user's, never another user's answer.
    static String cacheKey(Icon icon, Context context, String ownerPackage, String box) {
        if (icon.getType() == Icon.TYPE_DATA) {
            try {
                final MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update(icon.getDataBytes(), icon.getDataOffset(), icon.getDataLength());
                final StringBuilder hex = new StringBuilder("data:").append(context.getUserId()).append(":s")
                        .append(com.android.internal.diamaneos.cleancopies.CleanCopyClient.userSerial(
                                context, context.getUserId())).append(':');
                for (byte b : digest.digest()) {
                    hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
                }
                return hex.append(':').append(box).toString();
            } catch (NoSuchAlgorithmException | RuntimeException e) {
                return null;
            }
        }
        // The content behind a URI can change: a decode is reused for at most two windows of URI_CACHE_MS.
        return uriKey(icon, context, ownerPackage, box, SystemClock.uptimeMillis() / URI_CACHE_MS);
    }

    private static String uriKey(Icon icon, Context context, String ownerPackage, String box, long window) {
        final int sourceUser = android.content.ContentProvider.getUserIdFromUri(icon.getUri(), context.getUserId());
        return "uri:" + context.getUserId() + ":s"
                + com.android.internal.diamaneos.cleancopies.CleanCopyClient.userSerial(context, sourceUser)
                + ":r" + com.android.internal.diamaneos.cleancopies.CleanCopyClient.userSerial(
                        context, context.getUserId())
                + ':' + ownerPackage + ':' + bounded(String.valueOf(icon.getUri())) + ':' + box + ":t" + window;
    }

    /** URIs longer than this are not opened: no image needs one, and its key would hold a lot of memory. */
    static final int MAX_URI_CHARS = 2048;
    private static final int MAX_KEY_PART_CHARS = 200;

    /**
     * {@code part} of a key as it is when short, else a SHA-256 of it: keys of caches, retries and waiters stay small
     * however long the URI or shortcut id an app chose.
     */
    static String bounded(String part) {
        if (part == null || part.length() <= MAX_KEY_PART_CHARS) {
            return part;
        }
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(part.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder("sha256-");
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return part.substring(0, MAX_KEY_PART_CHARS);
        }
    }
}
