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

import static org.junit.Assert.*;
import android.app.slice.Slice;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.widget.RemoteViews;
import org.junit.Test;

public class AutofillPresentationUsersTest {
    private RemoteViews views(String uri) {
        RemoteViews views = new RemoteViews("android", android.R.layout.activity_list_item);
        views.setImageViewUri(android.R.id.icon, Uri.parse(uri));
        return views;
    }
    private Slice slice(String uri) {
        return new Slice.Builder(Uri.parse("content://test/presentation"), null)
                .addIcon(Icon.createWithContentUri(uri), null, java.util.List.of()).build();
    }
    @Test public void workQualifiedRemoteViews_surviveTheWorkSessionFilter() {
        RemoteViews work = views("content://10@images/icon");
        assertSame(work, AutofillPresentationUsers.sanitizeRemoteView(work, 10));
    }
    @Test public void workQualifiedSlice_survivesTheWorkSessionFilter() {
        Slice work = slice("content://10@images/icon");
        assertSame(work, AutofillPresentationUsers.sanitizeSlice(work, 10));
    }
    @Test public void unqualifiedPresentation_usesTheSessionUser() {
        assertNotNull(AutofillPresentationUsers.sanitizeRemoteView(views("content://images/icon"), 10));
        assertNotNull(AutofillPresentationUsers.sanitizeSlice(slice("content://images/icon"), 10));
    }
    @Test public void anotherUsersUri_isRejected() {
        assertNull(AutofillPresentationUsers.sanitizeRemoteView(views("content://0@images/icon"), 10));
        assertNull(AutofillPresentationUsers.sanitizeSlice(slice("content://0@images/icon"), 10));
    }
    @Test public void nestedSlice_cannotHideAnotherUsersIcon() {
        Slice nested = new Slice.Builder(Uri.parse("content://test/outer"), null)
                .addSubSlice(slice("content://0@images/icon"), null).build();
        assertNull(AutofillPresentationUsers.sanitizeSlice(nested, 10));
    }
    @Test public void unsafeSchemeAndUnknownUser_areRejected() {
        assertNull(AutofillPresentationUsers.sanitizeSlice(slice("file:///data/local/private"), 10));
        assertNull(AutofillPresentationUsers.sanitizeRemoteView(views("content://images/icon"), -10000));
    }
}
