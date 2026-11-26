/* Copyright (C) 2026 DiamaneOS */
package android.graphics;

/** Records application drawing once, then replays it without invoking application code during playback. */
final class GateAnimationPostProcess {
    static PostProcessor record(PostProcessor processor, int width, int height) {
        if (processor == null) return null;
        final Picture picture = new Picture();
        final Canvas recording = picture.beginRecording(width, height);
        final int format;
        try {
            format = processor.onPostProcess(recording);
        } finally {
            picture.endRecording();
        }
        if (format != PixelFormat.OPAQUE && format != PixelFormat.TRANSLUCENT && format != PixelFormat.UNKNOWN) {
            throw new IllegalArgumentException("invalid return from postProcess: " + format);
        }
        return canvas -> {
            picture.draw(canvas);
            return format;
        };
    }

    private GateAnimationPostProcess() { }
}
