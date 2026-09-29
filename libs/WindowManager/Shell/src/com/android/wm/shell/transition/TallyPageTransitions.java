/*
 * Copyright (C) 2026 The DiamaneOS Project
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

package com.android.wm.shell.transition;

import static android.app.ActivityOptions.ANIM_CUSTOM;
import static android.app.ActivityOptions.ANIM_FROM_STYLE;
import static android.app.ActivityOptions.ANIM_NONE;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_FLAG_AOD_APPEARING;
import static android.view.WindowManager.TRANSIT_FLAG_IS_RECENTS;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_APPEARING;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_LOCKED;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_OCCLUDING;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_UNOCCLUDING;
import static android.view.WindowManager.TRANSIT_FLAG_OPEN_BEHIND;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.window.TransitionInfo.FLAGS_IS_NON_APP_WINDOW;
import static android.window.TransitionInfo.FLAG_IN_TASK_WITH_EMBEDDED_ACTIVITY;
import static android.window.TransitionInfo.FLAG_IS_DISPLAY;
import static android.window.TransitionInfo.FLAG_IS_OCCLUDED;
import static android.window.TransitionInfo.FLAG_IS_VOICE_INTERACTION;
import static android.window.TransitionInfo.FLAG_NO_ANIMATION;
import static android.window.TransitionInfo.FLAG_SHOW_WALLPAPER;
import static android.window.TransitionInfo.FLAG_TASK_LAUNCHING_BEHIND;
import static android.window.TransitionInfo.FLAG_TRANSLUCENT;

import static com.android.internal.policy.TransitionAnimation.WALLPAPER_TRANSITION_NONE;
import static com.android.wm.shell.transition.TransitionAnimationHelper.getTransitionTypeFromInfo;

import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.ArraySet;
import android.view.SurfaceControl;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.Transformation;
import android.window.TransitionInfo;

import com.android.internal.R;
import com.android.internal.policy.AttributeCache;
import com.android.internal.policy.TransitionAnimation;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.shared.TransactionPool;
import com.android.wm.shell.shared.TransitionUtil;
import com.android.wm.shell.shared.animation.Interpolators;
import com.android.wm.shell.tally.TallyMotionTokens;
import com.android.wm.shell.tally.TallyPageMotion;
import com.android.wm.shell.tally.TallyWmShell;

import java.util.Set;
import java.util.function.Consumer;

/**
 * DiamaneOS Tally: activities and tasks open and close as the Tally prototype's pages
 * ({@link TallyPageMotion}). A page that opens enters from 16 dp to the side, in the reading
 * direction, on the slab spring and fades in on the fill spring; a page that closes scales to 0.9
 * on slab, its corners rounding as it goes, and fades out on fill; the page under either stays.
 *
 * <p>Only the motion changes: {@link DefaultTransitionHandler} asks {@link #isPageTransition}
 * whether a transition is one where it would play the framework's default activity or task
 * animation for every window that moves, and then takes this class's animation in place of that
 * default one, for the same windows. Everything else keeps stock's animation: transitions of Home
 * and the wallpaper (Launcher's), the keyguard, dreams, translucent windows, multi-window and
 * freeform tasks, rotations, and any app that set its own transition (ActivityOptions, {@code
 * overridePendingTransition}, {@code overrideActivityTransition} or its theme's window
 * animations), as well as the consent and credential screens in {@link #EXEMPT_PACKAGES} and
 * {@link #EXEMPT_ACTIVITIES}.
 */
final class TallyPageTransitions {
    /** The tags of the jank monitor's task-to-task interaction for Tally's pages. */
    static final String CUJ_TAG_OPEN = "TallyPageOpen";
    static final String CUJ_TAG_CLOSE = "TallyPageClose";

    /**
     * Transition flags for which Tally keeps stock's animation: the keyguard in any state (locked,
     * appearing, going away, occluded or revealed by an app shown over it), the AOD, Recents and a
     * task opening behind another.
     */
    private static final int STOCK_TRANSITION_FLAGS = TRANSIT_FLAG_KEYGUARD_LOCKED
            | TRANSIT_FLAG_KEYGUARD_GOING_AWAY | TRANSIT_FLAG_KEYGUARD_APPEARING
            | TRANSIT_FLAG_KEYGUARD_OCCLUDING | TRANSIT_FLAG_KEYGUARD_UNOCCLUDING
            | TRANSIT_FLAG_AOD_APPEARING | TRANSIT_FLAG_IS_RECENTS | TRANSIT_FLAG_OPEN_BEHIND;

    /**
     * Change flags for which Tally keeps stock's animation: translucent windows (dialogs and
     * sheets, including every permission, install and USB dialog), voice interaction, activities
     * embedded in a split task, occluded windows, windows over the wallpaper, a task launching
     * behind, displays and windows that ask for no animation.
     */
    private static final int STOCK_CHANGE_FLAGS = FLAG_TRANSLUCENT | FLAG_IS_VOICE_INTERACTION
            | FLAG_IN_TASK_WITH_EMBEDDED_ACTIVITY | FLAG_IS_OCCLUDED | FLAG_SHOW_WALLPAPER
            | FLAG_TASK_LAUNCHING_BEHIND | FLAG_IS_DISPLAY | FLAG_NO_ANIMATION;

    /**
     * Packages whose every screen keeps stock's animation: they only ask for consent (installing,
     * uninstalling and unarchiving apps, VPN, passkeys and passwords), or are SystemUI's own
     * system dialogs (USB permission, confirmation and debugging, screen capture, sensor unblock).
     */
    static final Set<String> EXEMPT_PACKAGES = Set.of(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.vpndialogs",
            "com.android.credentialmanager",
            "com.android.systemui");

    /**
     * Consent and credential screens that keep stock's animation, for readability before they take
     * a tap: the permission grant, restricted settings, the legacy permission review and the
     * default-app request (PermissionController), and Settings' device-admin activation and
     * credential confirmation.
     */
    static final Set<ComponentName> EXEMPT_ACTIVITIES = exemptActivities();

    private TallyPageTransitions() {}

    /**
     * Whether {@code info} moves as Tally's pages: Tally is on, the transition opens or closes
     * activities or tasks and nothing else, stock would play the framework's default animation
     * for every one of them, and none is exempt.
     */
    static boolean isPageTransition(@NonNull TransitionInfo info, int wallpaperTransit,
            boolean isDreamTransition, @NonNull TransitionAnimation transitionAnimation) {
        if (!TallyWmShell.isEnabled()) return false;
        final int infoType = info.getType();
        if (infoType != TRANSIT_OPEN && infoType != TRANSIT_TO_FRONT
                && infoType != TRANSIT_CLOSE && infoType != TRANSIT_TO_BACK) {
            return false;
        }
        if (wallpaperTransit != WALLPAPER_TRANSITION_NONE || isDreamTransition
                || info.isKeyguardGoingAway()
                || (info.getFlags() & STOCK_TRANSITION_FLAGS) != 0) {
            return false;
        }
        final int type = getTransitionTypeFromInfo(info);
        boolean hasPage = false;
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            if (change.hasFlags(FLAGS_IS_NON_APP_WINDOW)) continue;
            if ((change.getFlags() & STOCK_CHANGE_FLAGS) != 0
                    || change.getStartRotation() != change.getEndRotation()) {
                return false;
            }
            final int mode = change.getMode();
            if (mode == TRANSIT_CHANGE || !TransitionInfo.isIndependent(change, info)) continue;
            if (!TransitionUtil.isOpenOrCloseMode(mode)) return false;
            final ActivityManager.RunningTaskInfo task = change.getTaskInfo();
            if (task != null && task.getWindowingMode() != WINDOWING_MODE_FULLSCREEN) return false;
            if (task == null && change.getActivityComponent() == null) return false;
            if (isExempt(change) || !playsDefaultAnimation(change, type, transitionAnimation)) {
                return false;
            }
            hasPage = true;
        }
        return hasPage;
    }

    /** The jank monitor tag for a page transition. */
    static String cujTag(@NonNull TransitionInfo info) {
        return TransitionUtil.isOpeningType(getTransitionTypeFromInfo(info))
                ? CUJ_TAG_OPEN : CUJ_TAG_CLOSE;
    }

    /**
     * The page animation for {@code change} in a page transition of {@code type}: the opening
     * page's in an opening transition, the closing page's in a closing one, and none for the page
     * under it, which stays.
     */
    @Nullable
    static Animation loadPageAnimation(@NonNull Context context, int type,
            @NonNull TransitionInfo.Change change) {
        final boolean opening = TransitionUtil.isOpeningType(type);
        if (opening != TransitionUtil.isOpeningType(change.getMode())) return null;
        final TallyPageMotion motion = TallyMotionTokens.pageMotion(context.getResources());
        if (opening) {
            final boolean rtl = context.getResources().getConfiguration().getLayoutDirection()
                    == View.LAYOUT_DIRECTION_RTL;
            return new OpenAnimation(motion, rtl);
        }
        return new CloseAnimation(motion);
    }

    /**
     * Builds the window animation of a closing page: as {@link DefaultSurfaceAnimator}'s, and the
     * page's corners follow its scale.
     */
    static WindowAnimation buildCloseAnimation(@NonNull CloseAnimation anim,
            @NonNull TransitionInfo.Change change,
            @NonNull Consumer<WindowAnimation> finishCallback, @NonNull TransactionPool pool,
            @NonNull ShellExecutor mainExecutor, @Nullable Point position, @NonNull Rect clipRect,
            @Nullable TransitionAnimationHelper.RoundedContentPerDisplay roundedBounds) {
        final CloseAdapter adapter =
                new CloseAdapter(anim, change.getLeash(), position, clipRect, roundedBounds);
        return DefaultSurfaceAnimator.buildWindowAnimation(anim, change, adapter, finishCallback,
                pool, mainExecutor, 0f /* cornerRadius */);
    }

    private static boolean isExempt(@NonNull TransitionInfo.Change change) {
        final ActivityManager.RunningTaskInfo task = change.getTaskInfo();
        if (task != null) {
            return isExempt(task.topActivity) || isExempt(task.baseActivity)
                    || isExempt(task.realActivity);
        }
        return isExempt(change.getActivityComponent());
    }

    private static boolean isExempt(@Nullable ComponentName component) {
        return component != null && (EXEMPT_PACKAGES.contains(component.getPackageName())
                || EXEMPT_ACTIVITIES.contains(component));
    }

    /**
     * Whether stock plays the framework's default animation for {@code change}: the app set no
     * transition of its own, or one that stock ignores (a custom animation on a task that does not
     * override the task transition, or a window animation style on a task), or its window
     * animation style gives the framework's default animation.
     */
    private static boolean playsDefaultAnimation(@NonNull TransitionInfo.Change change, int type,
            @NonNull TransitionAnimation transitionAnimation) {
        final TransitionInfo.AnimationOptions options = change.getAnimationOptions();
        if (options == null) return true;
        final boolean isTask = change.getTaskInfo() != null;
        switch (options.getType()) {
            case ANIM_NONE:
                return true;
            case ANIM_CUSTOM:
                return isTask && !options.getOverrideTaskTransition();
            case ANIM_FROM_STYLE:
                if (isTask) return true;
                final int attr = animAttr(type, TransitionUtil.isOpeningType(change.getMode()));
                return attr != 0
                        && TransitionAnimationHelper.getCustomActivityTransition(attr, options)
                                == null
                        && isDefaultStyleAnimation(options.getPackageName(),
                                options.getAnimations(), attr, transitionAnimation);
            default:
                return false;
        }
    }

    /** The window animation attribute stock loads for an activity change, as it chooses it. */
    private static int animAttr(int type, boolean enter) {
        switch (type) {
            case TRANSIT_OPEN:
                return enter ? R.styleable.WindowAnimation_activityOpenEnterAnimation
                        : R.styleable.WindowAnimation_activityOpenExitAnimation;
            case TRANSIT_TO_FRONT:
                return enter ? R.styleable.WindowAnimation_taskToFrontEnterAnimation
                        : R.styleable.WindowAnimation_taskToFrontExitAnimation;
            case TRANSIT_CLOSE:
                return enter ? R.styleable.WindowAnimation_activityCloseEnterAnimation
                        : R.styleable.WindowAnimation_activityCloseExitAnimation;
            case TRANSIT_TO_BACK:
                return enter ? R.styleable.WindowAnimation_taskToBackEnterAnimation
                        : R.styleable.WindowAnimation_taskToBackExitAnimation;
            default:
                return 0;
        }
    }

    /**
     * Whether the app's window animation style {@code styleResId} gives the framework's default
     * animation for {@code attr}, as the window manager decides for predictive Back
     * ({@code BackNavigationController.isCustomizeExitAnimation}). No style means stock plays no
     * animation, so it is not the default.
     */
    private static boolean isDefaultStyleAnimation(@Nullable String packageName, int styleResId,
            int attr, @NonNull TransitionAnimation transitionAnimation) {
        if (styleResId == 0) return false;
        final String stylePackage = packageName == null
                || (styleResId & 0xFF000000) == 0x01000000 ? "android" : packageName;
        final AttributeCache cache = AttributeCache.instance();
        final AttributeCache.Entry entry = cache == null ? null
                : cache.get(stylePackage, styleResId, R.styleable.WindowAnimation);
        return entry != null && entry.array.getResourceId(attr, 0)
                == transitionAnimation.getDefaultAnimationResId(attr);
    }

    private static Set<ComponentName> exemptActivities() {
        final ArraySet<ComponentName> set = new ArraySet<>();
        for (String pkg : new String[] {"com.android.permissioncontroller",
                "com.google.android.permissioncontroller"}) {
            set.add(new ComponentName(pkg,
                    "com.android.permissioncontroller.permission.ui.GrantPermissionsActivity"));
            set.add(new ComponentName(pkg,
                    "com.android.permissioncontroller.ecm.EnhancedConfirmationDialogActivity"));
            set.add(new ComponentName(pkg,
                    "com.android.permissioncontroller.permission.ui.ReviewPermissionsActivity"));
            set.add(new ComponentName(pkg,
                    "com.android.permissioncontroller.role.ui.RequestRoleActivity"));
        }
        for (String cls : new String[] {
                "applications.specialaccess.deviceadmin.DeviceAdminAdd",
                "password.ConfirmDeviceCredentialActivity",
                "password.ConfirmDeviceCredentialActivity$InternalActivity",
                "password.ConfirmLockPassword",
                "password.ConfirmLockPassword$InternalActivity",
                "password.ConfirmLockPattern",
                "password.ConfirmLockPattern$InternalActivity"}) {
            set.add(new ComponentName("com.android.settings", "com.android.settings." + cls));
        }
        return set;
    }

    /**
     * A page opening: from 16 dp towards the end of the reading direction to its place on the slab
     * spring, fading in on the fill spring. The springs run in closed form over the animation's
     * duration, which the platform scales (transition animation scale).
     */
    static final class OpenAnimation extends Animation {
        private final TallyPageMotion mMotion;
        private final float mDirection;
        private final long mMillis;

        OpenAnimation(@NonNull TallyPageMotion motion, boolean rtl) {
            mMotion = motion;
            mDirection = rtl ? -1f : 1f;
            mMillis = motion.getOpenMillis();
            setDuration(mMillis);
            setInterpolator(Interpolators.LINEAR);
            setFillBefore(true);
            setFillAfter(true);
        }

        @Override
        protected void applyTransformation(float interpolatedTime, Transformation t) {
            if (interpolatedTime >= 1f) {
                t.getMatrix().reset();
                t.setAlpha(1f);
                return;
            }
            final float seconds = interpolatedTime * mMillis / 1000f;
            t.getMatrix().setTranslate(mDirection * mMotion.openOffset(seconds), 0f);
            t.setAlpha(mMotion.openAlpha(seconds));
        }
    }

    /**
     * A page closing: to 0.9 about its centre on the slab spring with a tap impulse, fading out on
     * the fill spring, over the time the fade takes. {@link CloseAdapter} rounds its corners.
     */
    static final class CloseAnimation extends Animation {
        final TallyPageMotion mMotion;
        private final long mMillis;
        private float mPivotX;
        private float mPivotY;

        CloseAnimation(@NonNull TallyPageMotion motion) {
            mMotion = motion;
            mMillis = motion.getCloseMillis();
            setDuration(mMillis);
            setInterpolator(Interpolators.LINEAR);
            setFillBefore(true);
            setFillAfter(true);
        }

        @Override
        public void initialize(int width, int height, int parentWidth, int parentHeight) {
            super.initialize(width, height, parentWidth, parentHeight);
            mPivotX = width / 2f;
            mPivotY = height / 2f;
        }

        @Override
        protected void applyTransformation(float interpolatedTime, Transformation t) {
            final float seconds = Math.min(interpolatedTime, 1f) * mMillis / 1000f;
            final float scale = mMotion.closeScale(1f, seconds);
            t.getMatrix().setScale(scale, scale, mPivotX, mPivotY);
            t.setAlpha(interpolatedTime >= 1f ? 0f : mMotion.closeAlpha(seconds));
        }
    }

    /**
     * Applies a {@link CloseAnimation} to a leash each frame, as {@link DefaultSurfaceAnimator}'s
     * adapter does (matrix, opacity, crop to the window), and sets the corner radius the page's
     * scale gives. Nothing is allocated per frame.
     */
    private static final class CloseAdapter extends DefaultSurfaceAnimator.AnimationAdapter {
        private final float[] mMatrix = new float[9];
        private final CloseAnimation mAnim;
        @Nullable private final Point mPosition;
        private final Rect mClipRect;
        private final int mWindowBottom;
        @Nullable private final TransitionAnimationHelper.RoundedContentPerDisplay mRoundedBounds;

        CloseAdapter(@NonNull CloseAnimation anim, @NonNull SurfaceControl leash,
                @Nullable Point position, @NonNull Rect clipRect,
                @Nullable TransitionAnimationHelper.RoundedContentPerDisplay roundedBounds) {
            super(leash);
            mAnim = anim;
            mPosition = position != null && (position.x != 0 || position.y != 0)
                    ? position : null;
            mClipRect = clipRect;
            mWindowBottom = clipRect.bottom;
            mRoundedBounds = roundedBounds;
        }

        @Override
        void applyTransformation(@NonNull ValueAnimator animator, long currentPlayTime) {
            final Transformation transformation = mTransformation;
            final SurfaceControl.Transaction t = mTransaction;
            transformation.clear();
            mAnim.getTransformation(currentPlayTime, transformation);
            final Matrix matrix = transformation.getMatrix();
            matrix.getValues(mMatrix);
            final float scale = mMatrix[Matrix.MSCALE_X];
            if (mPosition != null) {
                matrix.postTranslate(mPosition.x, mPosition.y);
            }
            t.setMatrix(mLeash, matrix, mMatrix);
            t.setAlpha(mLeash, transformation.getAlpha());
            if (mRoundedBounds != null) {
                mClipRect.bottom = Math.min(mRoundedBounds.mBounds.bottom, mWindowBottom);
            }
            t.setWindowCrop(mLeash, mClipRect);
            t.setCornerRadius(mLeash, mAnim.mMotion.cornerRadius(scale));
        }
    }
}
