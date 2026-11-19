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

import android.app.ActivityManager;
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
import java.util.function.Predicate;

/**
 * The autofill UI of the system server (its dataset picker and fill dialog) shows an autofill service's views. Their
 * images are made in the protected VM on an image thread once per response and fill in when made ({@link LateImages}),
 * nothing of them is decoded in the system server, and a UI whose views cannot be checked is not shown. Used on the
 * UI thread only. The upstream classes keep their code: they take the context from {@link #contextFor} and run their
 * UI through {@link #safely}.
 */
public final class AutofillViews {
    private static final String TAG = "VmDecode";

    private final Context mContext;
    private final Handler mShowing;
    private final Predicate<RemoteViews> mOfThisUser;
    // The late images of each response shown, for as long as the session keeps the response.
    private final WeakHashMap<FillResponse, LateImages> mLateImages = new WeakHashMap<>();

    /**
     * {@code context}: the system server's own. {@code showing}: the UI thread's handler. {@code ofThisUser}: true for
     * views whose URIs are all of the user shown to (the autofill service's own check): views of another user show
     * without images.
     */
    public AutofillViews(Context context, Handler showing, Predicate<RemoteViews> ofThisUser) {
        mContext = context;
        mShowing = showing;
        mOfThisUser = ofThisUser;
    }

    /**
     * The context to build a UI for {@code response} with: the response's views prepared in place (their images fill
     * in once made) and their resources behind the image gate. Throws IllegalArgumentException when a view of the
     * response cannot be checked: then none may be shown.
     */
    public Context contextFor(FillResponse response, String servicePackageName, Context caller) {
        LateImages images = mLateImages.get(response);
        if (images == null) {
            images = new LateImages(mContext, servicePackageName, ActivityManager.getCurrentUser(), mShowing);
            if (!prepare(response, images)) {
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

    // Every view of the response the fill UI or the fill dialog may show. Views of another user show without images.
    private boolean prepare(FillResponse response, LateImages images) {
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
            final boolean ok = mOfThisUser.test(v) ? images.prepareInPlace(v) : NoImageViews.stripInPlace(v);
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
