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

import android.content.ContentProvider;
import android.content.Context;
import android.os.Handler;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.util.Log;
import android.view.autofill.AutofillId;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The autofill UI of the system server (its dataset picker and fill dialog) shows an autofill service's views. Their
 * images are made in the protected VM on an image thread once per response and fill in when made ({@link LateImages}),
 * nothing of them is decoded in the system server, and a UI whose views cannot be checked is not shown. Used on the
 * UI thread only. The upstream classes keep their code: they take the context from {@link #contextFor} and run their
 * UI through {@link #safely}. Every image is made for the user of the autofill session (a work profile's session
 * while its parent is in front too): its URIs are opened as that user, for the service installed there.
 */
public final class AutofillViews {
    private static final String TAG = "VmDecode";

    private final Context mContext;
    private final Handler mShowing;
    // The late images of each response shown, for as long as the session keeps the response.
    private final WeakHashMap<FillResponse, LateImages> mLateImages = new WeakHashMap<>();

    /** {@code context}: the system server's own. {@code showing}: the UI thread's handler. */
    public AutofillViews(Context context, Handler showing) {
        mContext = context;
        mShowing = showing;
    }

    /**
     * The context to build a UI for {@code response} of the session of {@code userId} with: the response's views
     * prepared in place (their images fill in once made) and their resources behind the image gate. Throws
     * IllegalArgumentException when a view of the response cannot be checked, or the session's user is not known:
     * then none may be shown.
     */
    public Context contextFor(FillResponse response, String servicePackageName, int userId, Context caller) {
        LateImages images = mLateImages.get(response);
        if (images == null) {
            images = imagesFor(mContext, servicePackageName, userId, mShowing);
            if (!prepare(response, images, userId)) {
                throw new IllegalArgumentException("views of the autofill service cannot be checked");
            }
            images.start();
            mLateImages.put(response, images);
        }
        return images.context(caller);
    }

    /**
     * Runs {@code listener} on the showing thread once the images of {@code response} are filled in, so a UI measured
     * before can measure again. Nothing when the response has no images being made.
     */
    public void whenFilled(FillResponse response, Runnable listener) {
        final LateImages images = mLateImages.get(response);
        if (images != null) {
            images.whenFilled(listener);
        }
    }

    /**
     * {@code ui}, which builds an autofill UI, so that one that throws (its views cannot be checked, or cannot be
     * applied) shows nothing and {@code onFailure} cleans up: the system server keeps running.
     */
    public static Runnable safely(Runnable ui, Runnable onFailure) {
        return () -> {
            try {
                ui.run();
            } catch (RuntimeException e) {
                Log.e(TAG, "autofill UI not shown", e);
                try {
                    onFailure.run();
                } catch (RuntimeException e2) {
                    Log.e(TAG, "autofill UI not cleaned up", e2);
                }
            }
        };
    }

    /**
     * The late images of a UI of the session of {@code userId} (the save UI's too), for the views of
     * {@code servicePackageName}. Throws IllegalArgumentException when the session's user is not known.
     */
    public static LateImages imagesFor(Context context, String servicePackageName, int userId, Handler showing) {
        if (userId < 0) {
            throw new IllegalArgumentException("the autofill session's user is not known");
        }
        return new LateImages(context, servicePackageName, userId, showing);
    }

    /**
     * {@link LateImages#preparedCopy} of {@code views} for the session of {@code userId}: views naming a URI of
     * another user show without images. Null when they cannot be checked.
     */
    public static RemoteViews preparedCopy(LateImages images, RemoteViews views, int userId) {
        if (views == null || ofUser(views, userId)) {
            return images.preparedCopy(views);
        }
        try {
            final RemoteViews copy = views.clone();
            return NoImageViews.stripInPlace(copy) ? copy : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // True when every URI of views is one of userId: a URI without a user is opened as the session's user.
    static boolean ofUser(RemoteViews views, int userId) {
        final AtomicBoolean ok = new AtomicBoolean(true);
        try {
            views.visitUris(uri -> {
                if (ContentProvider.getUserIdFromUri(uri, userId) != userId) {
                    ok.set(false);
                }
            });
        } catch (RuntimeException e) {
            return false;
        }
        return ok.get();
    }

    // Every view of the response the fill UI or the fill dialog may show. Views of another user show without images.
    private boolean prepare(FillResponse response, LateImages images, int userId) {
        final List<RemoteViews> views = new ArrayList<>();
        views.add(response.getPresentation());
        views.add(response.getHeader());
        views.add(response.getFooter());
        views.add(response.getDialogPresentation());
        views.add(response.getDialogHeader());
        final List<Dataset> datasets = response.getDatasets();
        if (datasets != null) {
            for (Dataset dataset : datasets) {
                final ArrayList<AutofillId> ids = dataset.getFieldIds();
                final int count = ids == null ? 0 : ids.size();
                for (int i = 0; i < count; i++) {
                    views.add(dataset.getFieldPresentation(i));
                    views.add(dataset.getFieldDialogPresentation(i));
                }
            }
        }
        for (RemoteViews v : views) {
            if (v == null) {
                continue;
            }
            final boolean ok = ofUser(v, userId) ? images.prepareInPlace(v) : NoImageViews.stripInPlace(v);
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
