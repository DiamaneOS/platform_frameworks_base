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
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.RemotableViewMethod;
import android.view.View;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.List;

/**
 * A view of the test app that a RemoteViews layout may use, and that keeps what it was handed: the Icons, links and
 * Bundles the framework's own apply code gives it. The tests check these, not the converter's own view of the layout.
 */
@RemoteViews.RemoteView
public class ProbeView extends View {
    public final List<Icon> icons = new ArrayList<>();
    public final List<Uri> uris = new ArrayList<>();
    public final List<Bundle> bundles = new ArrayList<>();

    public ProbeView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @RemotableViewMethod
    public void setImageIcon(Icon icon) {
        icons.add(icon);
    }

    @RemotableViewMethod
    public void setImageURI(Uri uri) {
        uris.add(uri);
    }

    /** Like MessagingLayout.setData: the Bundle carries Person avatars. */
    @RemotableViewMethod
    public void setData(Bundle data) {
        bundles.add(data);
    }

    /** Like CompactMessagingLayout.setGroupFacePile. */
    @RemotableViewMethod
    public void setGroupFacePile(Bundle data) {
        bundles.add(data);
    }
}
