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
import android.content.res.AssetManager;
import android.content.res.ImageFileGate;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LruCache;
import android.util.TypedValue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The image file gate of the resources one notification's layouts are inflated with (see {@link ImageFileGate}):
 * every bitmap file (PNG, JPEG, WebP, GIF and so on) from an APK outside the system image is decoded in the VM and
 * charged to the posting app. That covers {@code setImageViewResource}, background and other drawable resource
 * actions, drawables named in the app's layout XML, and bitmap files inside the app's XML drawables ({@code <bitmap>},
 * {@code <nine-patch>}, layer lists and so on). Files of the system image load as before.
 *
 * <p>Fail closed: an image that cannot be made in the notification's budget, over its caps, or on the main thread
 * becomes an empty drawable, and the rest of the layout still shows. Nine-patches keep their stretching (the chunk is
 * read and checked in Java). Decoded files are cached by APK (path, time and size), file and density.
 */
public final class AppResourceImages implements ImageFileGate {
    private static final String TAG = "VmDecode";
    private static final int MAX_FILE_BYTES = 8 * 1024 * 1024;

    // The nine-patch chunk (or NO_CHUNK) of a cached file, by the same key as its bitmap in VmIcons' cache.
    private static final Object NO_CHUNK = new Object();
    private static final LruCache<String, Object> sChunks = new LruCache<>(512);
    private static volatile byte[] sEmptyPng;

    private final Context mContext;
    private final String mOwner;
    private final ImageBudget mBudget;
    private final int mMaxWidth;
    private final int mMaxHeight;
    private final Set<String> mFiles = new LinkedHashSet<>(); // guarded by itself

    /**
     * {@code context} is SystemUI's own (it binds the decode service), {@code ownerPackage} the posting app, which
     * every decode is charged to. Images are decoded to fit {@code maxWidth} x {@code maxHeight}.
     */
    public AppResourceImages(Context context, String ownerPackage, ImageBudget budget, int maxWidth,
            int maxHeight) {
        mContext = context;
        mOwner = ownerPackage == null ? "" : ownerPackage;
        mBudget = budget;
        mMaxWidth = Math.max(1, maxWidth);
        mMaxHeight = Math.max(1, maxHeight);
    }

    @Override
    public boolean decodesHere(String apkPath) {
        return SystemImagePaths.isSystemImage(apkPath);
    }

    @Override
    public Drawable loadImage(Resources res, TypedValue value, String apkPath) {
        final Made made = make(res, value, apkPath, null);
        if (made == null) {
            return empty();
        }
        if (made.chunk != null) {
            final NinePatchChunk c = made.chunk;
            return new NinePatchDrawable(res, made.bitmap, c.toDeviceFormat(ByteOrder.nativeOrder()),
                    new Rect(c.paddingLeft, c.paddingTop, c.paddingRight, c.paddingBottom), null);
        }
        return new BitmapDrawable(res, made.bitmap);
    }

    @Override
    public InputStream openImage(Resources res, TypedValue value, String apkPath, InputStream original) {
        byte[] bytes = null;
        try {
            if (!onMainThread()) {
                bytes = readAll(original);
            }
        } catch (IOException e) {
            Log.w(TAG, "app image file not read: " + e.getMessage());
        } finally {
            try {
                original.close();
            } catch (IOException ignored) {
                // Nothing to do.
            }
        }
        final Made made = bytes == null ? null : make(res, value, apkPath, bytes);
        if (made == null) {
            return new ByteArrayInputStream(emptyPng());
        }
        byte[] png = encode(made.bitmap);
        if (png != null && made.chunk != null) {
            png = NinePatchChunk.insertChunk(png, "npTc", made.chunk.toFileFormat());
        }
        return new ByteArrayInputStream(png != null ? png : emptyPng());
    }

    @Override
    public Drawable onXmlDrawableFailed(Resources res, TypedValue value, Exception e) {
        Log.w(TAG, "app XML drawable not loaded, empty: " + value.string + ": " + e);
        return empty();
    }

    /** The bitmap files of APKs outside the system image this gate was asked for, for tests and logs. */
    public List<String> files() {
        synchronized (mFiles) {
            return new ArrayList<>(mFiles);
        }
    }

    /** An empty drawable: what a layout shows for an image that could not be made. */
    public static Drawable empty() {
        return new ColorDrawable(Color.TRANSPARENT);
    }

    private static final class Made {
        final Bitmap bitmap;
        final NinePatchChunk chunk;

        Made(Bitmap bitmap, NinePatchChunk chunk) {
            this.bitmap = bitmap;
            this.chunk = chunk;
        }
    }

    // The pixels of one file, from the cache or the VM, or null. bytes: the file when the caller read it already.
    private Made make(Resources res, TypedValue value, String apkPath, byte[] bytes) {
        final String file = value.string == null ? null : value.string.toString();
        if (file == null) {
            return null;
        }
        synchronized (mFiles) {
            mFiles.add(file);
        }
        final int density = bitmapDensity(value);
        final String key = key(apkPath, file, density);
        final String admitKey = key != null ? key : "nocache:" + apkPath + ':' + file + ':' + density;
        final ImageBudget.Admission admission = mBudget.admit(admitKey);
        if (admission == ImageBudget.Admission.REFUSED) {
            Log.w(TAG, "too many images in one notification layout, empty: " + file);
            return null;
        }
        Made made = key == null ? null : cached(key);
        if (made == null) {
            if (onMainThread()) {
                // Never decoded on the main thread: an empty image now, the real one on the next inflation.
                return null;
            }
            try {
                if (bytes == null) {
                    bytes = readFile(res.getAssets(), value.assetCookie, file);
                }
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "app image file not read, empty: " + file + ": " + e.getMessage());
                return null;
            }
            made = decode(bytes, density, file);
            if (made == null) {
                return null;
            }
            if (key != null) {
                VmIcons.cachePut(key, made.bitmap);
                sChunks.put(key, made.chunk != null ? made.chunk : NO_CHUNK);
            }
        }
        if (admission == ImageBudget.Admission.NEW
                && !mBudget.charge(admitKey, (long) made.bitmap.getWidth() * made.bitmap.getHeight())) {
            Log.w(TAG, "notification layout images over their pixel cap, empty: " + file);
            return null;
        }
        return made;
    }

    private Made cached(String key) {
        final Bitmap bitmap = VmIcons.cacheGet(key);
        final Object chunk = sChunks.get(key);
        if (bitmap == null || chunk == null) {
            return null;
        }
        return new Made(bitmap, chunk instanceof NinePatchChunk ? (NinePatchChunk) chunk : null);
    }

    private Made decode(byte[] bytes, int density, String file) {
        final long left = mBudget.remainingMs();
        if (left <= 0) {
            Log.w(TAG, "notification image budget spent, empty: " + file);
            return null;
        }
        // A nine-patch is decoded at its own size, so its stretch lines still match.
        final NinePatchChunk chunk = NinePatchChunk.fromPng(bytes, bytes.length);
        final int maxWidth = chunk != null ? Math.min(chunk.width, VmDecode.MAX_BOX) : mMaxWidth;
        final int maxHeight = chunk != null ? Math.min(chunk.height, VmDecode.MAX_BOX) : mMaxHeight;
        final long start = SystemClock.uptimeMillis();
        Bitmap bitmap;
        try {
            mBudget.countVmCall();
            bitmap = VmDecode.decodeBytes(mContext, bytes, 0, bytes.length, mOwner,
                    VmDecode.PRIORITY_USER_WAITING, maxWidth, maxHeight, VmDecode.deadlineIn(left));
        } catch (VmDecode.UnavailableException | VmDecode.RejectedException e) {
            Log.w(TAG, "app image file not decoded, empty: " + file + ": " + e.getMessage());
            return null;
        } finally {
            mBudget.spent(SystemClock.uptimeMillis() - start);
        }
        bitmap = VmIcons.immutable(bitmap);
        bitmap.setDensity(density);
        final boolean keepsChunk = chunk != null && chunk.fits(bitmap.getWidth(), bitmap.getHeight());
        return new Made(bitmap, keepsChunk ? chunk : null);
    }

    // By APK (path, time and size, so an update decodes again), file in it, density and box. Null when the APK is
    // not known: such a file is never cached, so two packages can never share an entry.
    private String key(String apkPath, String file, int density) {
        if (apkPath == null) {
            return null;
        }
        final File apk = new File(apkPath);
        return "rfile:" + apkPath + ':' + apk.lastModified() + ':' + apk.length() + ':' + file + ':' + density
                + ':' + mMaxWidth + 'x' + mMaxHeight;
    }

    private static int bitmapDensity(TypedValue value) {
        if (value.density == TypedValue.DENSITY_DEFAULT) {
            return DisplayMetrics.DENSITY_DEFAULT;
        }
        if (value.density == TypedValue.DENSITY_NONE) {
            return Bitmap.DENSITY_NONE;
        }
        return value.density;
    }

    private static byte[] readFile(AssetManager assets, int cookie, String file) throws IOException {
        // The file's bytes as an opaque blob: never decoded here.
        try (InputStream in = assets.openNonAsset(cookie, file, AssetManager.ACCESS_STREAMING)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) {
            if (out.size() + n > MAX_FILE_BYTES) {
                throw new IOException("image file too large");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    // A PNG this process writes from checked pixels, for callers that decode a stream themselves.
    private static byte[] encode(Bitmap bitmap) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        return bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) ? out.toByteArray() : null;
    }

    private static byte[] emptyPng() {
        byte[] png = sEmptyPng;
        if (png == null) {
            final Bitmap one = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            png = encode(one);
            one.recycle();
            sEmptyPng = png;
        }
        return png;
    }

    private static boolean onMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }
}
