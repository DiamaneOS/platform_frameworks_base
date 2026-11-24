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

package com.android.server.autofill;

import android.app.slice.Slice;
import android.app.slice.SliceItem;
import android.content.ContentProvider;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.UserHandle;
import android.widget.RemoteViews;

import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/** DiamaneOS: every autofill presentation is checked for the session user before rendering. */
public final class AutofillPresentationUsers {
    private AutofillPresentationUsers() {}

    public static Context context(Context context, int userId) {
        if (userId < 0) throw new IllegalArgumentException("the autofill session user is unknown");
        return context.getUserId() == userId ? context : context.createContextAsUser(UserHandle.of(userId), 0);
    }

    private static boolean allowed(Uri uri, int userId) {
        if (uri == null) return true;
        return userId >= 0 && ContentProvider.getUserIdFromUri(uri, userId) == userId
                && ("content".equalsIgnoreCase(uri.getScheme())
                        || "android.resource".equalsIgnoreCase(uri.getScheme()));
    }

    public static RemoteViews sanitizeRemoteView(RemoteViews views, int userId) {
        if (views == null || userId < 0) return null;
        final AtomicBoolean ok = new AtomicBoolean(true);
        try {
            views.visitUris(uri -> { if (!allowed(uri, userId)) ok.set(false); });
            return ok.get() ? views : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static Slice sanitizeSlice(Slice slice, int userId) {
        if (slice == null || userId < 0) return null;
        final ArrayDeque<Slice> pending = new ArrayDeque<>();
        pending.add(slice);
        int items = 0;
        try {
            while (!pending.isEmpty()) {
                for (SliceItem item : pending.removeFirst().getItems()) {
                    if (++items > 256) return null;
                    if (SliceItem.FORMAT_SLICE.equals(item.getFormat())
                            || SliceItem.FORMAT_ACTION.equals(item.getFormat())) {
                        pending.add(item.getSlice());
                    } else if (SliceItem.FORMAT_IMAGE.equals(item.getFormat())) {
                        final Icon icon = item.getIcon();
                        if ((icon.getType() == Icon.TYPE_URI || icon.getType() == Icon.TYPE_URI_ADAPTIVE_BITMAP)
                                && !allowed(icon.getUri(), userId)) return null;
                    }
                }
            }
            return slice;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
