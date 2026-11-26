/* Copyright (C) 2026 DiamaneOS */
package android.graphics;

/** The Movie API backed by checked GIF frames, without a native compressed-image decoder. */
final class GatedMovie extends Movie {
    private final GatedAnimation mAnimation;
    private final int mDuration;
    private final RectF mTarget = new RectF();
    private long mTime = -1;
    private int mFrame;
    private int mLastDrawn = -1;

    GatedMovie(GatedAnimation animation) {
        mAnimation = animation;
        int duration = 0;
        for (int frame = 0; frame < animation.frameCount(); frame++) {
            duration += animation.frameDuration(frame);
        }
        mDuration = duration;
    }

    @Override public int width() { return mAnimation.getIntrinsicWidth(); }
    @Override public int height() { return mAnimation.getIntrinsicHeight(); }
    @Override public int duration() { return mDuration; }
    @Override public boolean isOpaque() { return false; }

    @Override public boolean setTime(int milliseconds) {
        final long time = Math.min(Integer.toUnsignedLong(milliseconds), Integer.toUnsignedLong(mDuration));
        if (time == mTime) return false;
        mTime = time;
        long end = 0;
        mFrame = mAnimation.frameCount() - 1;
        for (int frame = 0; frame < mAnimation.frameCount(); frame++) {
            end += mAnimation.frameDuration(frame);
            if (end >= time) {
                mFrame = frame;
                break;
            }
        }
        return mFrame != mLastDrawn;
    }

    @Override public void draw(Canvas canvas, float x, float y, Paint paint) {
        if (mTime == -1) setTime(0);
        mTarget.set(x, y, x + width(), y + height());
        canvas.drawBitmap(mAnimation.frameBitmap(mFrame), null, mTarget, paint);
        mLastDrawn = mFrame;
    }

    @Override public void draw(Canvas canvas, float x, float y) { draw(canvas, x, y, null); }
}
