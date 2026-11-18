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
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.RemoteViews;

/**
 * Another app's RemoteViews shown in the system server (the autofill dataset picker, its dialog and the save dialog),
 * with none of that app's compressed images decoded there and no wait for the VM on the system server's threads: URI
 * and data Icons and image links become empty images, and the views are applied with resources whose bitmap files of
 * apps outside the system image come out empty. XML (layouts, vectors) and the system image's files load as before,
 * text and the rest of the views too. Nothing is decoded anywhere.
 */
public final class NoImageViews {
    private static final String TAG = "VmDecode";

    private NoImageViews() {
    }

    /**
     * A copy of {@code views} with every URI and data image replaced by an empty image, or null when the views hold
     * something that cannot be checked (they are then not shown). The original is not changed.
     */
    public static RemoteViews withoutImages(RemoteViews views) {
        if (views == null) {
            return null;
        }
        try {
            final RemoteViews copy = RemoteViewsImages.deepCopy(views);
            return RemoteViewsImages.convert(copy, EMPTY) ? copy : null;
        } catch (RuntimeException e) {
            Log.w(TAG, "views from another app not shown: " + e);
            return null;
        }
    }

    /**
     * {@code context} with resources, and every package context made from it, whose bitmap files of apps outside the
     * system image come out empty. Apply another app's views with it: RemoteViews makes its package context from it.
     * Activities and intent senders started through it (a tapped link in the views) go to {@code context} itself, so
     * a wrapper of the caller that intercepts them stays in charge. Throws when the gated resources cannot be made:
     * the caller then shows nothing.
     */
    public static Context context(Context context) {
        // No budget, never an image thread here: the gate only ever finds nothing made, so it decodes nothing.
        final AppResourceImages gate = new AppResourceImages(context, "", context.getUserId(),
                new ImageBudget(0, 0, 0), 1, 1, false);
        return new Starts(VmImageContext.wrap(context, gate), context);
    }

    /**
     * Replaces, in place, every URI and data image of {@code views} (each may be null) by an empty image. For views
     * the system server owns already (a copy it unparcelled from the app). False when one of them cannot be checked:
     * then none may be shown.
     */
    public static boolean stripInPlace(RemoteViews... views) {
        for (RemoteViews v : views) {
            try {
                if (v != null && !RemoteViewsImages.convert(v, EMPTY)) {
                    return false;
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "views from another app not shown: " + e);
                return false;
            }
        }
        return true;
    }

    /**
     * The drawable {@code resId} of {@code packageName} for {@code userId}, without decoding any of its image files:
     * vectors and the system image's files load, its bitmap files do not. Null when nothing but an empty image is
     * left, or it cannot be loaded.
     */
    public static Drawable packageDrawable(Context context, String packageName, int userId, int resId) {
        if (resId == 0 || packageName == null) {
            return null;
        }
        try {
            final Context app = context.createPackageContextAsUser(packageName, 0, android.os.UserHandle.of(userId));
            final Drawable drawable = context(app).getDrawable(resId);
            return drawable == null || isEmpty(drawable) ? null : drawable;
        } catch (Exception e) {
            Log.w(TAG, "drawable of " + packageName + " not loaded: " + e);
            return null;
        }
    }

    /** True for the empty image a gate gives for a bitmap file it did not make. */
    public static boolean isEmpty(Drawable drawable) {
        return drawable instanceof ColorDrawable && ((ColorDrawable) drawable).getColor() == Color.TRANSPARENT;
    }

    // Resources and package contexts from the gated context. Starts, system services (the window manager, layout
    // inflaters), the display and whether it is a UI context from the caller's own context. A start that throws (no
    // new-task flag on a plain context) is logged, never thrown into the touch handling of the system server's UI
    // thread.
    static final class Starts extends ContextWrapper {
        private final Context mStarts;

        Starts(Context gated, Context starts) {
            super(gated);
            mStarts = starts;
        }

        // The context for another user (the fill UI applies the views with one) keeps both parts.
        @Override
        public Context createContextAsUser(android.os.UserHandle user, int flags) {
            return new Starts(getBaseContext().createContextAsUser(user, flags),
                    mStarts.createContextAsUser(user, flags));
        }

        @Override
        public Object getSystemService(String name) {
            return mStarts.getSystemService(name);
        }

        @Override
        public String getSystemServiceName(Class<?> serviceClass) {
            return mStarts.getSystemServiceName(serviceClass);
        }

        @Override
        public boolean isUiContext() {
            return mStarts.isUiContext();
        }

        @Override
        public android.view.Display getDisplay() {
            return mStarts.getDisplay();
        }

        @Override
        public void startActivity(Intent intent) {
            try {
                mStarts.startActivity(intent);
            } catch (RuntimeException e) {
                Log.w(TAG, "activity from another app's views not started: " + e);
            }
        }

        @Override
        public void startActivity(Intent intent, Bundle options) {
            try {
                mStarts.startActivity(intent, options);
            } catch (RuntimeException e) {
                Log.w(TAG, "activity from another app's views not started: " + e);
            }
        }

        @Override
        public void startActivities(Intent[] intents) {
            try {
                mStarts.startActivities(intents);
            } catch (RuntimeException e) {
                Log.w(TAG, "activities from another app's views not started: " + e);
            }
        }

        @Override
        public void startActivities(Intent[] intents, Bundle options) {
            try {
                mStarts.startActivities(intents, options);
            } catch (RuntimeException e) {
                Log.w(TAG, "activities from another app's views not started: " + e);
            }
        }

        @Override
        public void startIntentSender(IntentSender intent, Intent fillInIntent, int flagsMask, int flagsValues,
                int extraFlags) throws IntentSender.SendIntentException {
            try {
                mStarts.startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags);
            } catch (RuntimeException e) {
                Log.w(TAG, "intent from another app's views not sent: " + e);
            }
        }

        @Override
        public void startIntentSender(IntentSender intent, Intent fillInIntent, int flagsMask, int flagsValues,
                int extraFlags, Bundle options) throws IntentSender.SendIntentException {
            try {
                mStarts.startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags, options);
            } catch (RuntimeException e) {
                Log.w(TAG, "intent from another app's views not sent: " + e);
            }
        }
    }

    private static final RemoteViewsImages.Images EMPTY = new RemoteViewsImages.Images() {
        @Override
        public boolean needsWork(Icon icon) {
            return VmIcons.needsVm(icon);
        }

        @Override
        public Icon convert(Icon icon) {
            return VmIcons.emptyIcon();
        }

        @Override
        public Icon convert(Uri uri) {
            return VmIcons.emptyIcon();
        }
    };
}
