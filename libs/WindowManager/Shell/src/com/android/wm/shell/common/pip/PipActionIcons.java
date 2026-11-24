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

package com.android.wm.shell.common.pip;

import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import de.diamaneos.decode.client.ForeignIcons;

/**
 * DiamaneOS: the icons of a picture-in-picture menu's actions. The app's icon is never decoded in the Shell: pixels
 * and framework icons load as they are, data icons in the protected VM, the app's resources through an image gate
 * (their bitmap files decoded in the VM), made on an image thread for the app. The work is charged to the app that
 * made the action (its intent's creator, as the system records it), never to a package an icon names.
 */
public final class PipActionIcons {
    /** The most an action icon is decoded to. */
    private static final int ACTION_ICON_MAX_PX = 256;

    // The creator of each action intent, so the lookup (a call to the system) is made once per intent and not on
    // every update of the menu. Main thread.
    private final Map<PendingIntent, String> mCreators = new WeakHashMap<>();
    // Counts updates of the action views, so a late icon never lands on a newer action.
    private int mGeneration;

    /** Starts an update of the action views: icons still coming for an earlier one are dropped. */
    public int newUpdate() {
        return ++mGeneration;
    }

    /**
     * Loads {@code action}'s icon for the update {@code generation} and gives it to {@code onLoaded} on
     * {@code main}, unless a newer update started meanwhile or nothing could be loaded.
     */
    public void load(Context contextForUser, RemoteAction action, Handler main, int generation,
            Consumer<Drawable> onLoaded) {
        ForeignIcons.loadAsync(contextForUser, action.getIcon(), contextForUser.getUserId(), creatorOf(action),
                ACTION_ICON_MAX_PX, main::post, d -> {
                    if (d != null && generation == mGeneration) {
                        onLoaded.accept(d);
                    }
                });
    }

    // The package that created the action's intent, as the system records it, or "" when unknown.
    private String creatorOf(RemoteAction action) {
        final PendingIntent intent = action.getActionIntent();
        if (intent == null) {
            return "";
        }
        String creator = mCreators.get(intent);
        if (creator == null) {
            try {
                creator = intent.getCreatorPackage();
            } catch (RuntimeException e) {
                creator = null;
            }
            creator = creator != null ? creator : "";
            if (mCreators.size() > 64) {
                mCreators.clear();
            }
            mCreators.put(intent, creator);
        }
        return creator;
    }
}
