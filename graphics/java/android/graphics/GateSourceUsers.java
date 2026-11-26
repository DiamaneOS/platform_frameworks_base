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

package android.graphics;

import android.content.ContentInterface;
import android.content.ContentProvider;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.net.Uri;

import com.android.internal.diamaneos.cleancopies.GateOwner;

/** Captures the user behind a resolver capability before acquiring its bytes. */
final class GateSourceUsers {
    private static final int MAX_WRAPPERS = 16;

    private GateSourceUsers() {}

    static int of(ContentResolver resolver, Uri uri) {
        final String scheme = uri.getScheme();
        final boolean userAware = ContentResolver.SCHEME_CONTENT.equals(scheme)
                || ContentResolver.SCHEME_ANDROID_RESOURCE.equals(scheme);
        final int named = userAware ? ContentProvider.getUserIdFromUri(uri, GateOwner.UNKNOWN)
                : GateOwner.UNKNOWN;
        if (userAware && uri.getUserInfo() != null && named < 0) {
            throw new IllegalArgumentException("the image source user must be concrete");
        }
        final ContentInterface source = unwrap(resolver);
        // Wrapped non-content opens and stream fallbacks can select different sources.
        if (!ContentResolver.SCHEME_CONTENT.equals(scheme) && source != resolver) {
            throw new IllegalArgumentException("wrapped image sources require a content URI");
        }
        if (source instanceof ContentResolver) {
            return userAware ? GateOwner.uriUser(scheme,
                    named >= 0 ? named : ContentResolver.getThumbnailSourceUser((ContentResolver) source)) : GateOwner.UNKNOWN;
        }
        final int user;
        if (source instanceof ContentProviderClient) {
            user = ContentProviderClient.getThumbnailUserId((ContentProviderClient) source);
        } else if (source instanceof ContentProvider && ((ContentProvider) source).getContext() != null) {
            user = ((ContentProvider) source).getContext().getUserId();
        } else {
            throw new IllegalArgumentException("the image source user is unresolved");
        }
        // A wrapped provider handles the request before URI scheme dispatch.
        final int concrete = GateOwner.uriUser(ContentResolver.SCHEME_CONTENT, user);
        if (userAware && named >= 0 && named != concrete) {
            throw new IllegalArgumentException("the image URI user conflicts with the acquired source");
        }
        return concrete;
    }

    private static ContentInterface unwrap(ContentInterface source) {
        for (int depth = 0; source instanceof ContentResolver; depth++) {
            final ContentInterface next = ContentResolver.getThumbnailSource((ContentResolver) source);
            if (next == source) return source;
            if (depth >= MAX_WRAPPERS) {
                throw new IllegalArgumentException("the image source wrappers exceed the limit");
            }
            source = next;
        }
        return source;
    }
}
