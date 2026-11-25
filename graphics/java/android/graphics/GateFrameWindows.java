/* Copyright (C) 2026 DiamaneOS */
package android.graphics;

import android.annotation.Nullable;

import com.android.internal.diamaneos.cleancopies.GateOwner;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * An animation's frames for {@link GatedAnimation}, decoded in the VM a window at a time as playback reaches them,
 * as Android's AnimatedImageDrawable decodes its frames while it plays. Windows are aligned: window k holds frames
 * k * perWindow on. At most the window being shown and the next one are held. Later windows are asked on the gate's
 * frame thread, never on the thread that draws. A refused window ends the fetching, the drawable keeps its last
 * frame. Every window must match the first in frame count, loop count, frame size and colour space.
 */
final class GateFrameWindows {
    private static final long RETRY_MS = 1000;
    private static final int MOST_RETRIES = 5;
    private static ScheduledThreadPoolExecutor sFetcher; // guarded by GateFrameWindows.class

    /** The frames from first on, as the drawable shows them. */
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
    private final ColorSpace mColor;
    private final DecodeGate.Bytes mBytes; // null when every frame is held
    private final GateOwner mOwner;
    private final int mBoxWidth;
    private final int mBoxHeight;
    private final Frames mFrames;
    private Window mShown; // guarded by this
    private Window mNext; // guarded by this
    private int mAsked = -1; // guarded by this: the window being fetched
    private int mFailures; // guarded by this
    private boolean mRefused; // guarded by this
    private Runnable mArrived; // guarded by this

    private GateFrameWindows(Window first, int total, int loops, int frameWidth, int frameHeight, ColorSpace color,
            DecodeGate.Bytes bytes, GateOwner owner, int boxWidth, int boxHeight, Frames frames) {
        mShown = first;
        this.total = total;
        this.loops = loops;
        mPerWindow = first.frames.length;
        mFrameWidth = frameWidth;
        mFrameHeight = frameHeight;
        mColor = color;
        mBytes = bytes;
        mOwner = owner;
        mBoxWidth = boxWidth;
        mBoxHeight = boxHeight;
        mFrames = frames;
    }

    /** Every frame held already (Movie, an animation that fits one window). */
    static GateFrameWindows held(Bitmap[] frames, int[] durations, int loops) {
        return new GateFrameWindows(new Window(0, frames, durations), frames.length, loops, 0, 0, null, null, null,
                0, 0, null);
    }

    /**
     * The first window, asked on the calling thread as the decode call itself. The bytes stay shared with the
     * windows until they are collected.
     */
    static GateFrameWindows open(DecodeGate.Op op, DecodeGate.Bytes bytes, int boxWidth, int boxHeight,
            Frames frames) throws DecodeGate.Failure {
        final DecodeGate.Frames reply = DecodeGate.frameWindow(op, bytes, boxWidth, boxHeight, 0,
                DecodeMemory.MAX_FRAMES);
        final ColorSpace color = reply.frames.getColorSpace();
        final int width = reply.width, height = reply.height;
        final Bitmap[] first = frames.of(reply);
        final Window window = new Window(0, first, reply.durations.clone());
        if (first.length == reply.total) {
            return new GateFrameWindows(window, reply.total, reply.loops, width, height, color, null, null, 0, 0, null);
        }
        final DecodeGate.Bytes shared = bytes.share();
        final GateFrameWindows windows = new GateFrameWindows(window, reply.total, reply.loops, width, height, color,
                shared, op.owner, boxWidth, boxHeight, frames);
        // Given back once nothing (no drawable, no fetch under way) holds these windows.
        android.system.SystemCleaner.cleaner().register(windows, shared::close);
        return windows;
    }

    /** The frame when it is held, else null (and its window is asked for). */
    synchronized @Nullable Bitmap frame(int frame) {
        final Window w = windowOf(frame);
        if (w == null) {
            ask(frame / mPerWindow);
            return null;
        }
        return w.frames[frame - w.first];
    }

    /** The time of a held frame in milliseconds, -1 when it is not held. */
    synchronized int duration(int frame) {
        final Window w = windowOf(frame);
        return w == null ? -1 : w.durations[frame - w.first];
    }

    /**
     * The drawable shows this frame now: its window becomes the shown one, the one before is let go, and the next
     * window (after the last, the first again) is asked for.
     */
    synchronized void showing(int frame) {
        if (mBytes == null) return;
        if (mNext != null && mNext.holds(frame)) {
            mShown = mNext;
            mNext = null;
        }
        if (mShown == null || !mShown.holds(frame)) return;
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

    private Window windowOf(int frame) {
        if (mShown != null && mShown.holds(frame)) return mShown;
        if (mNext != null && mNext.holds(frame)) return mNext;
        return null;
    }

    private void ask(int window) {
        if (mBytes == null || mRefused || mAsked >= 0 || window < 0 || window * mPerWindow >= total) return;
        final int first = window * mPerWindow;
        if ((mShown != null && mShown.first == first) || (mNext != null && mNext.first == first)) return;
        mAsked = window;
        fetcher().execute(() -> fetch(first));
    }

    private void fetch(int first) {
        Window window = null;
        boolean refused = false;
        try (DecodeGate.Op op = DecodeGate.beginForSource(mOwner)) {
            op.checkFit(mBytes.reserved() + 3 * GatedDecodes.MAX_ANIMATION_BYTES);
            final DecodeGate.Frames reply = DecodeGate.frameWindow(op, mBytes, mBoxWidth, mBoxHeight, first,
                    mPerWindow);
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
                mNext = window;
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
