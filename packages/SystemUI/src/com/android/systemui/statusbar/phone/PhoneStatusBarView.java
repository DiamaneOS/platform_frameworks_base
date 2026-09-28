/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static com.android.systemui.Flags.edtNotAllowedOnStatusBar;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Region;
import android.util.AttributeSet;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.view.flags.Flags;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.android.internal.policy.SystemBarUtils;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.gesture.StatusBarLongPressGestureDetector;
import com.android.systemui.statusbar.phone.userswitcher.StatusBarUserSwitcherContainer;
import com.android.systemui.tally.TallyShell;
import com.android.systemui.user.ui.binder.StatusBarUserChipViewBinder;
import com.android.systemui.user.ui.viewmodel.StatusBarUserChipViewModel;
import com.android.systemui.util.leak.RotationUtils;

import java.util.Objects;
import java.util.function.BooleanSupplier;

public class PhoneStatusBarView extends FrameLayout {
    private static final String TAG = "PhoneStatusBarView";

    private int mRotationOrientation = -1;
    @Nullable
    private View mCutoutSpace;
    @Nullable
    private View mTallyStartSide;
    @Nullable
    private View mTallyEndSide;
    private int mTallyCutoutSpaceLeft = -1;
    @Nullable
    private DisplayCutout mDisplayCutout;
    @Nullable
    private Rect mDisplaySize;
    private int mStatusBarHeight;
    @Nullable
    private Gefingerpoken mTouchEventHandler;
    @Nullable
    private BooleanSupplier mIsStatusBarInteractiveSupplier;
    @Nullable
    private HasCornerCutoutFetcher mHasCornerCutoutFetcher;
    @Nullable
    private InsetsFetcher mInsetsFetcher;
    private int mDensity;
    private float mFontScale;
    private StatusBarLongPressGestureDetector mStatusBarLongPressGestureDetector;
    private final Region mTouchableRegion = Region.obtain();

    /**
     * Draw this many pixels into the left/right side of the cutout to optimally use the space
     */
    private int mCutoutSideNudge = 0;

    public PhoneStatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setLongPressGestureDetector(
            StatusBarLongPressGestureDetector statusBarLongPressGestureDetector) {
        mStatusBarLongPressGestureDetector = statusBarLongPressGestureDetector;
    }

    void setTouchEventHandler(Gefingerpoken handler) {
        mTouchEventHandler = handler;
    }

    void setIsStatusBarInteractiveSupplier(BooleanSupplier isStatusBarInteractiveSupplier) {
        mIsStatusBarInteractiveSupplier = isStatusBarInteractiveSupplier;
    }

    void setHasCornerCutoutFetcher(@NonNull HasCornerCutoutFetcher cornerCutoutFetcher) {
        mHasCornerCutoutFetcher = cornerCutoutFetcher;
        updateCutoutLocation();
    }

    void setInsetsFetcher(@NonNull InsetsFetcher insetsFetcher) {
        mInsetsFetcher = insetsFetcher;
        updateSafeInsets();
    }

    /**
     * With Tally, applies the content insets again (as padding) when they changed without a change
     * updateDisplayParameters() tracks: with the privacy dot's room at the end, a new layout
     * direction moves it to the other side. The sides are placed again as the view measures.
     */
    void tallyReapplyInsets() {
        updateSafeInsets();
        requestLayout();
    }

    void init(StatusBarUserChipViewModel viewModel) {
        StatusBarUserSwitcherContainer container = findViewById(R.id.user_switcher_container);
        StatusBarUserChipViewBinder.bind(container, viewModel);
    }

    /** Updates the status bar's touchable region. */
    public void updateTouchableRegion(Region touchableRegion) {
        mTouchableRegion.set(touchableRegion);
        getViewRootImpl().setTouchableRegion(touchableRegion);
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        mCutoutSpace = findViewById(R.id.cutout_space_view);
        if (TallyShell.isEnabled()) {
            mTallyStartSide = findViewById(R.id.status_bar_start_side_container);
            mTallyEndSide = findViewById(R.id.status_bar_end_side_container);
        }

        updateResources();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (updateDisplayParameters()) {
            updateLayoutForCutout();
        }
        if (edtNotAllowedOnStatusBar()) {
            // See b/482405546: the status bar sometimes uses a light theme, e.g. when the user's
            // wallpaper is light. The expanded dark theme feature inverts colors in light theme
            // view hierarchies so it may incorrectly invert the status bar in this case. To avoid
            // this we directly tell expanded dark theme to ignore this view hierarchy.
            getViewRootImpl().setForceInvertAllowed(false);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mDisplayCutout = null;
    }

    // Per b/300629388, we let the PhoneStatusBarView detect onConfigurationChanged to
    // updateResources, instead of letting the PhoneStatusBarViewController detect onConfigChanged
    // then notify PhoneStatusBarView.
    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();

        // May trigger cutout space layout-ing
        if (updateDisplayParameters()) {
            updateLayoutForCutout();
            requestLayout();
        }
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (updateDisplayParameters()) {
            updateLayoutForCutout();
            requestLayout();
        }
        return super.onApplyWindowInsets(insets);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (TallyShell.isEnabled()) {
            tallyPlaceSides();
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /**
     * @return boolean indicating if we need to update the cutout location / margins
     */
    private boolean updateDisplayParameters() {
        boolean changed = false;
        int newRotation = RotationUtils.getExactRotation(mContext);
        if (newRotation != mRotationOrientation) {
            changed = true;
            mRotationOrientation = newRotation;
        }

        if (!Objects.equals(getRootWindowInsets().getDisplayCutout(), mDisplayCutout)) {
            changed = true;
            mDisplayCutout = getRootWindowInsets().getDisplayCutout();
        }

        Configuration newConfiguration = mContext.getResources().getConfiguration();
        final Rect newSize = newConfiguration.windowConfiguration.getMaxBounds();
        if (!Objects.equals(newSize, mDisplaySize)) {
            changed = true;
            mDisplaySize = newSize;
        }

        int density = newConfiguration.densityDpi;
        if (density != mDensity) {
            changed = true;
            mDensity = density;
        }
        float fontScale = newConfiguration.fontScale;
        if (fontScale != mFontScale) {
            changed = true;
            mFontScale = fontScale;
        }
        return changed;
    }

    @Override
    public boolean onRequestSendAccessibilityEventInternal(View child, AccessibilityEvent event) {
        if (super.onRequestSendAccessibilityEventInternal(child, event)) {
            // The status bar is very small so augment the view that the user is touching
            // with the content of the status bar a whole. This way an accessibility service
            // may announce the current item as well as the entire content if appropriate.
            AccessibilityEvent record = AccessibilityEvent.obtain();
            onInitializeAccessibilityEvent(record);
            dispatchPopulateAccessibilityEvent(record);
            event.appendRecord(record);
            return true;
        }
        return false;
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        if (mIsStatusBarInteractiveSupplier != null
                && !mIsStatusBarInteractiveSupplier.getAsBoolean()) {
            // Consume the event to prevent any calls to #onHoverEvent on status bar view or its
            // components, essentially making the status bar and its children completely
            // non-interactive.
            return true;
        }
        return super.dispatchHoverEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (mIsStatusBarInteractiveSupplier != null
                && !mIsStatusBarInteractiveSupplier.getAsBoolean()) {
            // Consume the event to prevent any calls to #onTouchEvent on status bar view or its
            // components, essentially making the status bar and its children completely
            // non-interactive.
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Touch events outside of the touchable regions are still received by this view. Touch
        // events started within the view should not be handled to allow app handle views behind
        // the status bar to handle the event. ACTION_MOVE and ACTION_UP events outside the
        // touchable region should still be handled so that an open notification shade can be
        // correctly updated and closed.
        if (event.getAction() == MotionEvent.ACTION_DOWN
                && !mTouchableRegion.contains((int) event.getX(), (int) event.getY())) {
            return false;
        }

        if (mStatusBarLongPressGestureDetector != null && !Flags.scrollToTop()) {
            mStatusBarLongPressGestureDetector.handleTouch(event);
        }
        if (mTouchEventHandler == null) {
            if (Flags.scrollToTop()) {
                return false;
            }
            Log.w(
                    TAG,
                    String.format(
                            "onTouch: No touch handler provided; eating gesture at (%d,%d)",
                            (int) event.getX(),
                            (int) event.getY()
                    )
            );
            return true;
        }
        return mTouchEventHandler.onTouchEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return mTouchEventHandler != null
                ? mTouchEventHandler.onInterceptTouchEvent(event)
                : super.onInterceptTouchEvent(event);
    }

    public void updateResources() {
        mCutoutSideNudge = getResources().getDimensionPixelSize(
                R.dimen.display_cutout_margin_consumption);

        updateStatusBarHeight();
    }

    private void updateStatusBarHeight() {
        final int waterfallTopInset =
                mDisplayCutout == null ? 0 : mDisplayCutout.getWaterfallInsets().top;
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        mStatusBarHeight = SystemBarUtils.getStatusBarHeight(mContext);
        layoutParams.height = mStatusBarHeight - waterfallTopInset;
        updateSystemIconsContainerHeight();
        updatePaddings();
        setLayoutParams(layoutParams);
    }

    private void updateSystemIconsContainerHeight() {
        View systemIconsContainer = findViewById(R.id.system_icons);
        ViewGroup.LayoutParams layoutParams = systemIconsContainer.getLayoutParams();
        int newSystemIconsHeight =
                getResources().getDimensionPixelSize(R.dimen.status_bar_icon_container_height);
        if (layoutParams.height != newSystemIconsHeight) {
            layoutParams.height = newSystemIconsHeight;
            systemIconsContainer.setLayoutParams(layoutParams);
        }
    }

    private void updatePaddings() {
        int statusBarPaddingStart = getResources().getDimensionPixelSize(
                R.dimen.status_bar_padding_start);

        findViewById(R.id.status_bar_contents).setPaddingRelative(
                statusBarPaddingStart,
                getResources().getDimensionPixelSize(R.dimen.status_bar_padding_top),
                getResources().getDimensionPixelSize(R.dimen.status_bar_padding_end),
                0);

        findViewById(R.id.notification_lights_out)
                .setPaddingRelative(0, statusBarPaddingStart, 0, 0);

        findViewById(R.id.system_icons).setPaddingRelative(
                getResources().getDimensionPixelSize(R.dimen.status_bar_icons_padding_start),
                getResources().getDimensionPixelSize(R.dimen.status_bar_icons_padding_top),
                getResources().getDimensionPixelSize(R.dimen.status_bar_icons_padding_end),
                getResources().getDimensionPixelSize(R.dimen.status_bar_icons_padding_bottom)
        );
    }

    private void updateLayoutForCutout() {
        updateStatusBarHeight();
        updateCutoutLocation();
        updateSafeInsets();
    }

    private void updateCutoutLocation() {
        // Not all layouts have a cutout (e.g., Car)
        if (mCutoutSpace == null) {
            return;
        }

        boolean hasCornerCutout;
        if (mHasCornerCutoutFetcher != null) {
            hasCornerCutout = mHasCornerCutoutFetcher.fetchHasCornerCutout();
        } else {
            Log.e(TAG, "mHasCornerCutoutFetcher unexpectedly null");
            hasCornerCutout = true;
        }

        if (mDisplayCutout == null || mDisplayCutout.isEmpty() || hasCornerCutout) {
            mCutoutSpace.setVisibility(View.GONE);
            return;
        }

        mCutoutSpace.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mCutoutSpace.getLayoutParams();

        Rect bounds = mDisplayCutout.getBoundingRectTop();

        bounds.left = bounds.left + mCutoutSideNudge;
        bounds.right = bounds.right - mCutoutSideNudge;
        lp.width = bounds.width();
        lp.height = bounds.height();
        if (TallyShell.isEnabled()) {
            mTallyCutoutSpaceLeft = bounds.left;
        }
    }

    /**
     * Tally's privacy dot needs more room at the end of the status bar than the rounded corner
     * needs at the start, so the content is off the display's centre, and stock's even split
     * between the two sides would leave the cutout space off the cutout. With Tally the side left
     * of the cutout (the start side, or the end side in RTL) is sized to end where the space
     * starts, at the cutout, and the side right of it keeps its weight and takes the rest: the
     * start side keeps stock's room and the end side starts at the cutout's edge, as in stock.
     * Without the space, the sides share the content evenly, as in stock. This runs as the view
     * measures, once the layout direction and the padding (the insets) are settled.
     */
    private void tallyPlaceSides() {
        if (mCutoutSpace == null || mTallyStartSide == null || mTallyEndSide == null) {
            return;
        }
        boolean changed;
        if (mCutoutSpace.getVisibility() == View.VISIBLE && mTallyCutoutSpaceLeft >= 0) {
            boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            // status_bar_contents starts after this view's padding and its own.
            int contentLeft = getPaddingLeft() + getResources().getDimensionPixelSize(
                    rtl ? R.dimen.status_bar_padding_end : R.dimen.status_bar_padding_start);
            int leftWidth = Math.max(0, mTallyCutoutSpaceLeft - contentLeft);
            changed = tallySetSide(rtl ? mTallyEndSide : mTallyStartSide, leftWidth, 0f)
                    | tallySetSide(rtl ? mTallyStartSide : mTallyEndSide, 0, 1f);
        } else {
            changed = tallySetSide(mTallyStartSide, 0, 1f) | tallySetSide(mTallyEndSide, 0, 1f);
        }
        if (changed) {
            // status_bar_contents sizes the sides: it must measure again, even at the same size.
            ((View) mTallyStartSide.getParent()).forceLayout();
        }
    }

    /** Sets a side's width and weight (stock's are 0 and 1); returns whether they changed. */
    private static boolean tallySetSide(View side, int width, float weight) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) side.getLayoutParams();
        if (lp.width == width && lp.weight == weight) {
            return false;
        }
        lp.width = width;
        lp.weight = weight;
        return true;
    }

    private void updateSafeInsets() {
        if (mInsetsFetcher == null) {
            Log.e(TAG, "mInsetsFetcher unexpectedly null");
            return;
        }

        Insets insets = mInsetsFetcher.fetchInsets();
        setPadding(
                insets.left,
                insets.top,
                insets.right,
                getPaddingBottom());
    }

    interface HasCornerCutoutFetcher {
        boolean fetchHasCornerCutout();
    }

    interface InsetsFetcher {
        Insets fetchInsets();
    }
}
