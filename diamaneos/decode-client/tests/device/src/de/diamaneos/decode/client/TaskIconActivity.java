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
import android.app.ActivityManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;

/**
 * A task with an icon of its own (TaskIconOwnerDeviceTest): a bitmap icon in its task description, which the window
 * manager stores in its user's recent images folder. The colour comes with the intent, so each run has new bytes.
 */
public class TaskIconActivity extends Activity {
    public static final String EXTRA_COLOR = "color";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final Bitmap icon = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        icon.eraseColor(getIntent().getIntExtra(EXTRA_COLOR, Color.MAGENTA));
        setTaskDescription(new ActivityManager.TaskDescription("owner-task", icon));
    }
}
