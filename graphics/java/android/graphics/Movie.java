/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.graphics;

import android.compat.annotation.UnsupportedAppUsage;
import android.content.res.AssetManager;
import android.os.Build;

import java.io.FileInputStream;
import java.io.InputStream;

/**
 * @deprecated Prefer {@link android.graphics.drawable.AnimatedImageDrawable}.
 */
@Deprecated
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class Movie {
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private long mNativeMovie;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private Movie(long nativeMovie) {
        if (nativeMovie == 0) {
            throw new RuntimeException("native movie creation failed");
        }
        mNativeMovie = nativeMovie;
    }

    // DiamaneOS: a checked VM-frame subclass has no native movie instance.
    Movie() { }

    public native int width();
    public native int height();
    public native boolean isOpaque();
    public native int duration();

    public native boolean setTime(int relativeMilliseconds);

    private native void nDraw(long nativeCanvas, float x, float y, long paintHandle);

    public void draw(Canvas canvas, float x, float y, Paint paint) {
        nDraw(canvas.getNativeCanvasWrapper(), x, y,
                paint != null ? paint.getNativeInstance() : 0);
    }

    public void draw(Canvas canvas, float x, float y) {
        nDraw(canvas.getNativeCanvasWrapper(), x, y, 0);
    }

    public static Movie decodeStream(InputStream is) {
        if (is == null) {
            return null;
        }
        if (is instanceof AssetManager.AssetInputStream && !DecodeGate.gatesStream(is)) {
            final long asset = ((AssetManager.AssetInputStream) is).getNativeAsset();
            return nativeDecodeAsset(asset);
        }
        if (DecodeGate.gatesStream(is)) {
            // DiamaneOS: only checked VM frames back movies of gated inputs.
            return GatedDecodes.movie(is);
        }

        return nativeDecodeStream(is);
    }

    private static native Movie nativeDecodeAsset(long asset);
    private static native Movie nativeDecodeStream(InputStream is);
    public static Movie decodeByteArray(byte[] data, int offset, int length) {
        if (DecodeGate.gates()) {
            // DiamaneOS: validate the public range before reading bytes in the VM.
            if (data == null) throw new NullPointerException("data");
            if ((offset | length) < 0 || offset > data.length - length) {
                throw new ArrayIndexOutOfBoundsException();
            }
            return GatedDecodes.movie(data, offset, length);
        }
        return nativeDecodeByteArray(data, offset, length);
    }

    private static native Movie nativeDecodeByteArray(byte[] data, int offset, int length);

    private static native void nativeDestructor(long nativeMovie);

    public static Movie decodeFile(String pathName) {
        // DiamaneOS: acquire and verify the file within the entry budget.
        if (DecodeGate.gates()) return GatedDecodes.movie(pathName);
        InputStream is;
        try {
            is = new FileInputStream(pathName);
        }
        catch (java.io.FileNotFoundException e) {
            return null;
        }
        try {
            if (DecodeGate.gates() && DecodeGate.exempt(((FileInputStream) is).getFD())) {
                // DiamaneOS: a verified system file (DecodeGate).
                return decodeTempStream(is, true);
            }
        } catch (java.io.IOException e) {
            // Not exempt: through the gate below.
        }
        return decodeTempStream(is);
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            // DiamaneOS: VM-frame movies have no native instance to destroy.
            if (mNativeMovie != 0) nativeDestructor(mNativeMovie);
            mNativeMovie = 0;
        } finally {
            super.finalize();
        }
    }

    private static Movie decodeTempStream(InputStream is) {
        return decodeTempStream(is, false);
    }

    private static Movie decodeTempStream(InputStream is, boolean exempt) {
        Movie moov = null;
        try {
            moov = exempt ? nativeDecodeStream(is) : decodeStream(is);
            is.close();
        }
        catch (java.io.IOException e) {
            /*  do nothing.
                If the exception happened on open, moov will be null.
                If it happened on close, moov is still valid.
            */
        }
        return moov;
    }
}
