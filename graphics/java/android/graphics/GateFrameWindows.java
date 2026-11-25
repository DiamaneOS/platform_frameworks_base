/* Copyright (C) 2026 DiamaneOS */
package android.graphics;

import android.annotation.Nullable;

import com.android.internal.diamaneos.cleancopies.GateOwner;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * An animation's frames for {@link GatedAnimation}, decoded in the VM a window at a time as playback reaches them,
 * as Android's AnimatedImageDrawable decodes its frames while it plays. Windows are aligned: window k holds frames
 * k * perWindow on, at most WINDOW_BYTES of them (at least one frame). Frames already shown are let go, so playback
 * holds the rest of the shown window and the next one. A frame above one VM reply comes in bands of rows, one frame a
 * window. Later windows are asked on the gate's frame thread, never on the thread that draws. A refused window ends
 * the fetching, the drawable keeps its last frame. Every window must match the first in frame count, loop count,
 * frame size and colour space. The whole timeline of frame times is kept, charged as the drawable's memory.
 */
final class GateFrameWindows {
    private static final long RETRY_MS = 1000;
    private static final int MOST_RETRIES = 5;
    // The frames of one window at most, in bytes (at least one frame): stock keeps about two frames live.
    static final long WINDOW_BYTES = 8L << 20;
    private static ScheduledThreadPoolExecutor sFetcher; // guarded by GateFrameWindows.class

    /** The frames from first on, as the drawable shows them. Entries already shown are let go (null). */
    static final class Window {
        final int first;
        final Bitmap[] frames;
        final int[] durations;

        Window(int first, Bitmap[] frames, int[] durations) {
            this.first = first;
            this.frames = frames;
            this.durations = durations;
        }

        boolean holds(int frame) {
            return frame >= first && frame - first < frames.length;
        }
    }

    /** Builds a window's frames from a checked VM reply (colour space, post-processor), giving the reply back. */
    interface Frames {
        Bitmap[] of(DecodeGate.Frames reply) throws DecodeGate.Failure;
    }

    final int total;
    final int loops;
    private final int mPerWindow;
    private final int mFrameWidth;
    private final int mFrameHeight;
    private final boolean mBands; // a frame comes in bands of rows (above one VM reply)
    private final ColorSpace mColor;
    private final DecodeGate.Bytes mBytes; // null when every frame is held
    private final GateOwner mOwner;
    private final int mBoxWidth;
    private final int mBoxHeight;
    private final Frames mFrames;
    private final int[] mTimeline; // every frame's time once seen, -1 before
    private Window mShown; // guarded by this
    private Window mNext; // guarded by this
    private int mAsked = -1; // guarded by this: the window being fetched
    private int mFailures; // guarded by this
    private boolean mRefused; // guarded by this
    private Runnable mArrived; // guarded by this

    private GateFrameWindows(Window first, int total, int loops, int perWindow, int frameWidth, int frameHeight,
            boolean bands, ColorSpace color, DecodeGate.Bytes bytes, GateOwner owner, int boxWidth, int boxHeight,
            Frames frames, int[] timeline) {
        mShown = first;
        this.total = total;
        this.loops = loops;
        mPerWindow = perWindow;
        mFrameWidth = frameWidth;
        mFrameHeight = frameHeight;
        mBands = bands;
        mColor = color;
        mBytes = bytes;
        mOwner = owner;
        mBoxWidth = boxWidth;
        mBoxHeight = boxHeight;
        mFrames = frames;
        mTimeline = timeline;
        record(first);
    }

    /** Every frame held already (Movie). */
    static GateFrameWindows held(Bitmap[] frames, int[] durations, int loops) {
        return new GateFrameWindows(new Window(0, frames, durations), frames.length, loops, frames.length, 0, 0,
                false, null, null, null, 0, 0, null, durations);
    }

    /**
     * The first window, asked on the calling thread as the decode call itself (stock decodes the first frame
     * there). {@code canvasWidth} x {@code canvasHeight} is the animation's size: above one VM reply at the box's
     * size, frames come in bands. The bytes stay shared with the windows until they are collected.
     */
    static GateFrameWindows open(DecodeGate.Op op, DecodeGate.Bytes bytes, int boxWidth, int boxHeight,
            int canvasWidth, int canvasHeight, Frames frames) throws DecodeGate.Failure {
        final int bandWidth = Math.min(boxWidth, canvasWidth), bandHeight = Math.min(boxHeight, canvasHeight);
        final boolean bands = (long) bandWidth * bandHeight > DecodeGate.MAX_VM_PIXELS;
        // The VM's frame is at most the box: the window as many frames as WINDOW_BYTES holds of that.
        final int perWindow = bands ? 1 : (int) Math.max(1, Math.min(DecodeMemory.MAX_FRAMES,
                WINDOW_BYTES / Math.max(1, 4L * boxWidth * boxHeight)));
        final DecodeGate.Frames reply = bands
                ? assemble(op, bytes, bandWidth, bandHeight, 0)
                : DecodeGate.frameWindow(op, bytes, boxWidth, boxHeight, 0, perWindow);
        final ColorSpace color = reply.frames.getColorSpace();
        final int width = reply.width, height = reply.height, total = reply.total, loops = reply.loops;
        if (reply.count != Math.min(perWindow, total)) {
            DecodeMemory.recycle(reply.frames, null);
            throw DecodeGate.failed(false, "first frame window differs from the request");
        }
        final int[] timeline = new int[total];
        java.util.Arrays.fill(timeline, -1);
        try (DecodeMemory.Reservation memory = DecodeMemory.reserve(4L * total)) {
            DecodeMemory.keepTimeline(timeline, memory);
        } catch (DecodeMemory.NoRoomException e) {
            DecodeMemory.recycle(reply.frames, null);
            throw DecodeGate.noRoom(e);
        }
        final int[] durations = reply.durations.clone();
        final Window window = new Window(0, frames.of(reply), durations);
        if (window.frames.length == total) {
            return new GateFrameWindows(window, total, loops, perWindow, width, height, bands, color, null, null,
                    0, 0, null, timeline);
        }
        final DecodeGate.Bytes shared = bytes.share();
        final GateFrameWindows windows = new GateFrameWindows(window, total, loops, perWindow, width, height, bands,
                color, shared, op.owner, bands ? bandWidth : boxWidth, bands ? bandHeight : boxHeight, frames,
                timeline);
        // Given back once nothing (no drawable, no fetch under way) holds these windows.
        android.system.SystemCleaner.cleaner().register(windows, shared::close);
        return windows;
    }

    // One frame of width x height from its bands of rows, each checked, drawn into one bitmap as a one-frame reply.
    private static DecodeGate.Frames assemble(DecodeGate.Op op, DecodeGate.Bytes bytes, int width, int height,
            int frame) throws DecodeGate.Failure {
        op.checkFit(bytes.reserved() + 3 * 4L * width * height + DecodeGate.MAX_FRAMES_BYTES);
        Bitmap whole = null;
        DecodeGate.Frames band = null;
        Canvas canvas = null;
        boolean complete = false;
        try {
            int total = 0, loops = 0, duration = 0;
            ColorSpace color = null;
            final Paint copy = new Paint();
            copy.setBlendMode(BlendMode.SRC);
            for (int top = 0; top < height; ) {
                band = DecodeGate.frameBand(op, bytes, width, height, frame, top);
                if (whole == null) {
                    total = band.total;
                    loops = band.loops;
                    duration = band.durations[0];
                    color = band.frames.getColorSpace();
                    whole = GatePixels.blank(width, height, Bitmap.Config.ARGB_8888, true, color);
                    canvas = new Canvas(whole);
                } else if (band.total != total || band.loops != loops || band.durations[0] != duration
                        || !java.util.Objects.equals(color, band.frames.getColorSpace())) {
                    throw DecodeGate.failed(false, "frame bands differ");
                }
                canvas.drawBitmap(band.frames, 0, top, copy);
                top += band.height;
                DecodeMemory.recycle(band.frames, null);
                band = null;
            }
            canvas.setBitmap(null);
            canvas = null;
            whole.freezeForDecodeGate();
            complete = true;
            return new DecodeGate.Frames(whole, 1, width, height, new int[] {duration}, loops, frame, total);
        } finally {
            if (canvas != null) canvas.setBitmap(null);
            if (band != null) DecodeMemory.recycle(band.frames, null);
            if (!complete) DecodeMemory.recycle(whole, null);
        }
    }

    /** The frame when it is held, else null (and its window is asked for). */
    synchronized @Nullable Bitmap frame(int frame) {
        final Window w = windowOf(frame);
        final Bitmap bitmap = w == null ? null : w.frames[frame - w.first];
        if (bitmap == null) ask(frame / mPerWindow);
        return bitmap;
    }

    /** The time of a frame in milliseconds once seen (the whole timeline is kept), -1 before. */
    synchronized int duration(int frame) {
        if (frame < 0 || frame >= total) return -1;
        return mTimeline[frame];
    }

    /**
     * The drawable shows this frame now: its window becomes the shown one, the frames before it and the window before
     * are let go, and the next window (after the last, the first again) is asked for.
     */
    synchronized void showing(int frame) {
        if (mBytes == null) return;
        if (mNext != null && mNext.holds(frame)) {
            mShown = mNext;
            mNext = null;
        }
        if (mShown == null || !mShown.holds(frame)) return;
        for (int i = 0; i < frame - mShown.first; i++) mShown.frames[i] = null;
        final int windows = (total + mPerWindow - 1) / mPerWindow;
        ask((frame / mPerWindow + 1) % windows);
    }

    /** Runs (on the frame thread) when a window arrived or fetching ended for good. */
    synchronized void setArrived(@Nullable Runnable arrived) {
        mArrived = arrived;
    }

    /** True once no window can come any more (refused, or too many failures in a row). */
    synchronized boolean ended() {
        return mRefused;
    }

    private void record(Window w) {
        if (mTimeline == null) return;
        for (int i = 0; i < w.durations.length; i++) mTimeline[w.first + i] = w.durations[i];
    }

    private Window windowOf(int frame) {
        if (mShown != null && mShown.holds(frame)) return mShown;
        if (mNext != null && mNext.holds(frame)) return mNext;
        return null;
    }

    private void ask(int window) {
        if (mBytes == null || mRefused || mAsked >= 0 || window < 0 || (long) window * mPerWindow >= total) return;
        final int first = window * mPerWindow;
        if (mNext != null && mNext.first == first) return;
        if (mShown != null && mShown.first == first && mShown.frames[0] != null) return;
        mAsked = window;
        fetcher().execute(() -> fetch(first));
    }

    private void fetch(int first) {
        Window window = null;
        boolean refused = false;
        try (DecodeGate.Op op = DecodeGate.beginForSource(mOwner)) {
            op.checkFit(mBytes.reserved() + 3 * Math.max(GatedDecodes.MAX_ANIMATION_BYTES,
                    4L * mFrameWidth * mFrameHeight));
            final DecodeGate.Frames reply = mBands ? assemble(op, mBytes, mBoxWidth, mBoxHeight, first)
                    : DecodeGate.frameWindow(op, mBytes, mBoxWidth, mBoxHeight, first, mPerWindow);
            // The same animation as the first window: anything else is a reply not to be trusted, and final.
            if (reply.total != total || reply.loops != loops || reply.width != mFrameWidth
                    || reply.height != mFrameHeight || reply.count != Math.min(mPerWindow, total - first)
                    || !java.util.Objects.equals(reply.frames.getColorSpace(), mColor)) {
                DecodeMemory.recycle(reply.frames, null);
                refused = true;
            } else {
                final int[] durations = reply.durations.clone();
                window = new Window(first, mFrames.of(reply), durations);
            }
        } catch (DecodeGate.Failure e) {
            refused = e.refused;
        } catch (RuntimeException | OutOfMemoryError e) {
            refused = false;
        }
        final Runnable arrived;
        synchronized (this) {
            mAsked = -1;
            if (window != null) {
                mFailures = 0;
                record(window);
                if (mShown != null && mShown.first == first) {
                    mShown = window; // the shown window asked again (its frames were let go)
                } else {
                    mNext = window;
                }
            } else if (refused || ++mFailures >= MOST_RETRIES) {
                mRefused = true;
            }
            arrived = mArrived;
        }
        if (window == null && !refused && !ended()) {
            // Not now (the VM restarts, no room, too slow): asked again after a while, the last frame stays.
            final int retry = first / mPerWindow;
            fetcher().schedule(() -> {
                synchronized (GateFrameWindows.this) {
                    ask(retry);
                }
            }, RETRY_MS, TimeUnit.MILLISECONDS);
        }
        if (arrived != null) arrived.run();
    }

    private static synchronized ScheduledThreadPoolExecutor fetcher() {
        if (sFetcher == null) {
            sFetcher = new ScheduledThreadPoolExecutor(1, r -> {
                final Thread t = new Thread(r, "DecodeGateFrames");
                t.setDaemon(true);
                return t;
            });
            sFetcher.setKeepAliveTime(10, TimeUnit.SECONDS);
            sFetcher.allowCoreThreadTimeOut(true);
        }
        return sFetcher;
    }
}
