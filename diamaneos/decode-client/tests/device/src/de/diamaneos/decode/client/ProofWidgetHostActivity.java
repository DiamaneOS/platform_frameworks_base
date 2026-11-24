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

import android.app.Activity;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.util.SizeF;
import android.widget.FrameLayout;

import java.util.List;

/**
 * Hosts a conversation widget of SystemUI for the phone checks: binds one, opens its conversation picker, and shows
 * it large enough for a message image. The widget goes again when the activity ends.
 */
public class ProofWidgetHostActivity extends Activity {
    private static final String TAG = "ProofWidgetHost";
    private static final int HOST_ID = 4711;
    private static final int CONFIGURE = 1;
    private static final ComponentName PEOPLE = new ComponentName("com.android.systemui",
            "com.android.systemui.people.widget.PeopleSpaceWidgetProvider");

    private AppWidgetHost mHost;
    private FrameLayout mFrame;
    private int mId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mFrame = new FrameLayout(this);
        mFrame.setPadding(32, 300, 32, 32);
        setContentView(mFrame);
        mHost = new AppWidgetHost(this, HOST_ID);
        mHost.startListening();
        mId = mHost.allocateAppWidgetId();
        if (!AppWidgetManager.getInstance(this).bindAppWidgetIdIfAllowed(mId, PEOPLE)) {
            Log.e(TAG, "conversation widget not bound");
            finish();
            return;
        }
        mHost.startAppWidgetConfigureActivityForResult(this, mId, 0, CONFIGURE, null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CONFIGURE) {
            return;
        }
        Log.i(TAG, "conversation picked: " + (resultCode == RESULT_OK) + ", widget " + mId);
        final AppWidgetManager manager = AppWidgetManager.getInstance(this);
        final AppWidgetHostView view = mHost.createView(this, mId, manager.getAppWidgetInfo(mId));
        final float side = 300;
        view.updateAppWidgetSize(new Bundle(), List.of(new SizeF(side, side)));
        final int px = (int) (side * getResources().getDisplayMetrics().density);
        mFrame.addView(view, new FrameLayout.LayoutParams(px, px));
    }

    @Override
    protected void onDestroy() {
        if (mHost != null) {
            if (mId != -1) {
                mHost.deleteAppWidgetId(mId);
            }
            mHost.stopListening();
        }
        super.onDestroy();
    }
}
