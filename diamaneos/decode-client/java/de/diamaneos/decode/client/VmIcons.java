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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ApkAssets;
import android.content.res.Resources;
import android.util.TypedValue;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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
            return value.getAllocationByteCount();
        }
    };

    private VmIcons() {
    }

    static Bitmap cacheGet(String key) {
        return sCache.get(key);
    }

    static void cachePut(String key, Bitmap bitmap) {
        // One image may take at most a quarter of the cache, so it cannot flush the others.
        if (bitmap.getAllocationByteCount() <= MAX_CACHE_ENTRY_BYTES) {
            sCache.put(key, bitmap);
        }
    }

    private static final int MAX_CACHE_ENTRY_BYTES = 4 * 1024 * 1024;
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
        final Icon safe = icon.getType() == Icon.TYPE_URI_ADAPTIVE_BITMAP
                ? Icon.createWithAdaptiveBitmap(bitmap) : Icon.createWithBitmap(bitmap);
        if (icon.hasTint()) {
            safe.setTintList(icon.getTintList());
            safe.setTintBlendMode(icon.getTintBlendMode());
        }
        return safe;
    }

    /**
     * For views that load icons on the main thread (status bar, shelf, always-on display, bubbles): a VM icon is
     * replaced by {@code placeholder}, never decoded. Other icons are passed through.
     */
    public static Icon withoutVmIcon(Icon icon, Icon placeholder) {
        return needsVm(icon) ? placeholder : icon;
    }

    /**
     * For a notification whose images could not be converted: the icon when it holds no compressed bytes from an app
     * (pixels, a content URI that the viewer decodes in the VM, or a framework resource), otherwise null. Never
     * decodes and never reads another package.
     */
    public static Icon withoutAppImage(Icon icon) {
        if (icon == null) {
            return null;
        }
        switch (icon.getType()) {
            case Icon.TYPE_BITMAP:
            case Icon.TYPE_ADAPTIVE_BITMAP:
            case Icon.TYPE_URI:
            case Icon.TYPE_URI_ADAPTIVE_BITMAP:
                return icon;
            case Icon.TYPE_RESOURCE:
                return "android".equals(icon.getResPackage()) ? icon : null;
            default:
                return null;
        }
    }

    /** The VM-decoded bitmap of a VM icon by {@code deadline}, or null. Failures are logged, not thrown. */
    public static Bitmap decode(Icon icon, Context context, String ownerPackage, int maxPx, long deadline) {
        return decode(icon, context, ownerPackage, maxPx, maxPx, deadline);
    }

    /** {@link #decode(Icon, Context, String, int, long)} with a box instead of a square. */
    public static Bitmap decode(Icon icon, Context context, String ownerPackage, int maxWidth, int maxHeight,
            long deadline) {
        if (!needsVm(icon) || maxWidth < 1 || maxHeight < 1) {
            return null;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "icon decode on the main thread refused, placeholder shown");
            return null;
        }
        final String key = cacheKey(icon, context, ownerPackage, maxWidth + "x" + maxHeight);
        if (key != null) {
            final Bitmap cached = sCache.get(key);
            if (cached != null) {
                return cached;
            }
        }
        if (deadline != VmDecode.NO_DEADLINE && deadline <= SystemClock.uptimeMillis()) {
            // No time left: what is cached only. Nothing is opened or handed to the VM.
            return null;
        }
        try {
            Bitmap bitmap;
            if (icon.getType() == Icon.TYPE_DATA) {
                bitmap = VmDecode.decodeBytes(context, icon.getDataBytes(), icon.getDataOffset(),
                        icon.getDataLength(), ownerPackage, VmDecode.PRIORITY_USER_WAITING, maxWidth,
                        maxHeight, deadline);
            } else {
                bitmap = VmDecode.decodeUri(context, context.getContentResolver(), icon.getUri(), ownerPackage,
                        VmDecode.PRIORITY_USER_WAITING, maxWidth, maxHeight, deadline);
            }
            // Cached bitmaps are shared between views: an immutable copy, so nobody can draw into it.
            bitmap = immutable(bitmap);
            if (key != null) {
                cachePut(key, bitmap);
            }
            return bitmap;
        } catch (VmDecode.UnavailableException | VmDecode.RejectedException e) {
            Log.w(TAG, "icon not decoded, placeholder shown: " + e.getMessage());
            return null;
        }
    }

    /**
     * The compressed bytes behind a resource icon from an app's own package: a bitmap file (PNG, JPEG, WebP, GIF and
     * so on) in an APK that is not part of the system image. Null for anything else: other icon types, resources of
     * the system image (verified at boot), XML drawables (parsed by the framework's XML and vector code, not by an
     * image codec), and resources that cannot be read. The bytes are read as an opaque blob, never decoded here.
     */
    public static byte[] appBitmapResourceBytes(Icon icon, Context context, int userId) {
        return readAppResource(icon, context, userId);
    }

    private static byte[] readAppResource(Icon icon, Context context, int userId) {
        if (icon == null || icon.getType() != Icon.TYPE_RESOURCE) {
            return null;
        }
        final String pkg = icon.getResPackage();
        if (pkg == null || pkg.isEmpty() || "android".equals(pkg)) {
            return null;
        }
        try {
            final PackageManager pm = context.getPackageManager();
            final ApplicationInfo ai = pm.getApplicationInfoAsUser(pkg, 0, userId);
            final Resources res = pm.getResourcesForApplication(ai);
            final TypedValue value = new TypedValue();
            res.getValue(icon.getResId(), value, true);
            if (value.string == null) {
                return null;
            }
            // One test for the system image everywhere: the APK this file is in (an overlay from /data included).
            if (SystemImagePaths.isSystemImage(apkPath(res, value.assetCookie))) {
                return null;
            }
            final String path = value.string.toString();
            if (path.endsWith(".xml")) {
                // Not loaded here: SystemUI loads every app resource icon through its image gate.
                return null;
            }
            if (!path.startsWith("res/")) {
                return null;
            }
            try (InputStream in = res.getAssets().openNonAsset(value.assetCookie, path)) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                final byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    if (out.size() + n > MAX_RESOURCE_BYTES) {
                        throw new IOException("resource too large");
                    }
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            }
        } catch (PackageManager.NameNotFoundException | IOException | RuntimeException e) {
            Log.w(TAG, "app resource icon not read: " + e.getMessage());
            // Unreadable: treated as an app image that cannot be shown.
            return EMPTY;
        }
    }

    private static final byte[] EMPTY = new byte[0];
    private static final int MAX_RESOURCE_BYTES = 8 * 1024 * 1024;

    /**
     * True for a package whose APKs are all in the system image, by path (the one test used everywhere, see
     * {@link SystemImagePaths}). An updated system app is under /data, so it is not.
     */
    public static boolean isSystemImage(ApplicationInfo ai) {
        if (ai == null || !SystemImagePaths.isSystemImage(ai.sourceDir)) {
            return false;
        }
        if (ai.splitSourceDirs != null) {
            for (String split : ai.splitSourceDirs) {
                if (!SystemImagePaths.isSystemImage(split)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The path of the APK holding the files of {@code cookie} in {@code res}, or null. */
    static String apkPath(Resources res, int cookie) {
        try {
            final ApkAssets[] apks = res.getAssets().getApkAssets();
            if (cookie < 1 || cookie > apks.length) {
                return null;
            }
            final String path = apks[cookie - 1].getAssetPath();
            return path == null || path.isEmpty() ? null : path;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Like {@link #toSafeIcon(Icon, Context, String, int, int, long)}, and a resource icon that is a bitmap file in an
     * app's own APK is decoded in the VM too. For code that may wait (not the main thread). Null when such an icon
     * cannot be decoded. {@code userId} is the user whose copy of the package to read.
     */
    public static Icon toSafeIconWithResources(Icon icon, Context context, int userId, String ownerPackage,
            int maxWidth, int maxHeight, long deadline) {
        if (needsVm(icon)) {
            return toSafeIcon(icon, context, ownerPackage, maxWidth, maxHeight, deadline);
        }
        final byte[] bytes = readAppResource(icon, context, userId);
        if (bytes == null) {
            return icon;
        }
        if (bytes.length == 0 || Looper.myLooper() == Looper.getMainLooper()) {
            return null;
        }
        final String key = resourceKey(icon, context, userId, maxWidth + "x" + maxHeight);
        Bitmap bitmap = key == null ? null : sCache.get(key);
        if (bitmap == null) {
            if (deadline != VmDecode.NO_DEADLINE && deadline <= SystemClock.uptimeMillis()) {
                return null;
            }
            try {
                bitmap = VmDecode.decodeBytes(context, bytes, 0, bytes.length, ownerPackage,
                        VmDecode.PRIORITY_USER_WAITING, maxWidth, maxHeight, deadline);
            } catch (VmDecode.UnavailableException | VmDecode.RejectedException e) {
                Log.w(TAG, "app resource icon not decoded, placeholder shown: " + e.getMessage());
                return null;
            }
            bitmap = immutable(bitmap);
            if (key != null) {
                cachePut(key, bitmap);
            }
        }
        final Icon safe = Icon.createWithBitmap(bitmap);
        if (icon.hasTint()) {
            safe.setTintList(icon.getTintList());
            safe.setTintBlendMode(icon.getTintBlendMode());
        }
        return safe;
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
        final Bitmap bitmap = key == null ? null : sCache.get(key);
        return bitmap == null ? null
                : iconLike(icon, bitmap, icon.getType() == Icon.TYPE_URI_ADAPTIVE_BITMAP);
    }

    // A resource by user, package, its APK (path and time, so an update decodes again) and resource ID.
    static String resourceKey(Icon icon, Context context, int userId, String box) {
        try {
            final ApplicationInfo ai = context.getPackageManager().getApplicationInfoAsUser(
                    icon.getResPackage(), 0, userId);
            return "res:" + userId + ':' + icon.getResPackage() + ':' + ai.sourceDir + ':'
                    + new File(ai.sourceDir).lastModified() + ':' + icon.getResId() + ':' + box;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    // URI icons by user, owner and URI (as SystemUI's own image caches do), data icons by a hash of their bytes.
    static String cacheKey(Icon icon, Context context, String ownerPackage, String box) {
        if (icon.getType() == Icon.TYPE_DATA) {
            try {
                final MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update(icon.getDataBytes(), icon.getDataOffset(), icon.getDataLength());
                final StringBuilder hex = new StringBuilder("data:");
                for (byte b : digest.digest()) {
                    hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
                }
                return hex.append(':').append(box).toString();
            } catch (NoSuchAlgorithmException | RuntimeException e) {
                return null;
            }
        }
        // The content behind a URI can change: a decode is reused for at most URI_CACHE_MS.
        return "uri:" + context.getUserId() + ':' + ownerPackage + ':' + icon.getUri() + ':' + box + ":t"
                + SystemClock.uptimeMillis() / URI_CACHE_MS;
    }
}
