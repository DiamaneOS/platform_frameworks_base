/*
 * Copyright (C) 2026 DiamaneOS
 * Licensed under the Apache License, Version 2.0
 */
package android.graphics;

/** The public consumer decides whether checked incomplete image pixels may be published. */
final class GatePartialImages {
    private GatePartialImages() { }

    static void imageDecoder(int error, ImageDecoder.OnPartialImageListener listener, ImageDecoder.Source source)
            throws ImageDecoder.DecodeException {
        if (error == 0) return;
        if (error != 2 && error != 3) {
            throw new ImageDecoder.DecodeException(ImageDecoder.DecodeException.SOURCE_MALFORMED_DATA,
                    "unknown protected image source verdict", null, source);
        }
        final ImageDecoder.DecodeException exception = new ImageDecoder.DecodeException(error,
                "partial image from the protected VM", null, source);
        if (listener == null || !listener.onPartialImage(exception)) throw exception;
    }

    static void bitmapFactory(int error) throws DecodeGate.Failure {
        if (error != 0 && error != 2) throw DecodeGate.failed(true, "malformed partial image");
    }
}
