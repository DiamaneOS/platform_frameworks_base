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

package com.android.systemui.statusbar.policy;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;

import de.diamaneos.decode.client.ForeignIcons;
import de.diamaneos.decode.client.VmDecode;

import java.util.function.Consumer;

/**
 * DiamaneOS: the preview of an inline reply's attachment, an image another app (a keyboard, a paste or a drag) put
 * on the clip. Never decoded here: made in the protected VM on an image thread, in a lane of its own, and the main
 * thread never waits for it. Until it is made nothing shows. A link that cannot be opened or decoded shows nothing,
 * as upstream does for an image it cannot load.
 */
final class RemoteInputImages {
    private static final String OWNER = VmDecode.unknownOwner("remote-input");
    private static final int MAX_PX = 512;

    private RemoteInputImages() {
    }

    /** The preview of {@code uri} if it was made before, else null, and {@code onMade} gets it once it is made. */
    static Drawable load(Context context, Uri uri, Consumer<Drawable> onMade) {
        if (uri == null) {
            return null;
        }
        final Icon icon = Icon.createWithContentUri(uri);
        final int user = context.getUserId();
        return ForeignIcons.loadNoWait(context, icon, user, OWNER, MAX_PX, () -> {
            final Drawable made = ForeignIcons.loadNoWait(context, icon, user, OWNER, MAX_PX, null);
            if (made != null) {
                onMade.accept(made);
            }
        });
    }
}
