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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A task's icon read by a caller of user 0 (as SystemUI's recents and a launcher of the parent user read them) is an
 * image of the task's user: a task with a bitmap icon is started in a work profile made for the test, its icon file
 * read through ActivityTaskManager.getTaskDescriptionIcon from user 0, and the decode service's request line names
 * the work profile, not user 0. The profile is removed after. Reads the service's DecoderUsers lines (debuggable
 * builds).
 */
@RunWith(AndroidJUnit4.class)
public class TaskIconOwnerDeviceTest {
    private static final String TAG = "TaskIconOwnerTest";

    @Test
    public void aWorkTasksIconIsTheWorkUsersImage() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final String made = OwnerDeviceTest.shell("pm create-user --profileOf 0 --managed owner-task");
        final Matcher m = Pattern.compile("created user id (\\d+)").matcher(made);
        assertTrue("no work profile made: " + made, m.find());
        final int profile = Integer.parseInt(m.group(1));
        final double since = System.currentTimeMillis() / 1000.0;
        String path = null;
        try {
            Log.i(TAG, "start " + profile + ": " + OwnerDeviceTest.shell("am start-user -w " + profile).trim());
            Log.i(TAG, "install: " + OwnerDeviceTest.shell("pm install-existing --user " + profile + " "
                    + context.getPackageName()).trim());
            // RecentTasks omits tasks created before this test user's setup completes.
            OwnerDeviceTest.shell("settings --user " + profile + " put secure user_setup_complete 1");
            final int color = Color.rgb(30, (int) (SystemClock.uptimeMillis() % 250), 90);
            context.startActivityAsUser(new Intent(context, TaskIconActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(TaskIconActivity.EXTRA_COLOR, color), UserHandle.of(profile));
            // The icon file of the task, once the window manager wrote it.
            final long end = SystemClock.uptimeMillis() + 20_000;
            while (path == null && SystemClock.uptimeMillis() < end) {
                SystemClock.sleep(500);
                for (ActivityManager.RecentTaskInfo task : ActivityTaskManager.getService()
                        .getRecentTasks(20, 0, profile).getList()) {
                    if (task.baseActivity != null && TaskIconActivity.class.getName()
                            .equals(task.baseActivity.getClassName()) && task.taskDescription != null) {
                        path = task.taskDescription.getIconFilename();
                    }
                }
            }
            assertNotNull("the task's icon file", path);
            Log.i(TAG, "icon file of user " + profile + "'s task found");
            // Read from user 0 until the decode service saw it (the file is written a moment after the task starts).
            final Pattern line = Pattern.compile("request of user (\\d+) (shared|per-user) in VM start (\\d+)"
                    + ", .*, lane (?:\\?gate:uid" + Process.myUid() + "|\\d+/\\?gate:"
                    + Pattern.quote(context.getPackageName()) + "@" + Process.myUid() + "/bytes)(?:,|$)");
            final long until = SystemClock.uptimeMillis() + 20_000;
            String log = "";
            Matcher found = null;
            while (SystemClock.uptimeMillis() < until) {
                ActivityTaskManager.getService().getTaskDescriptionIcon(path, profile);
                SystemClock.sleep(1_500);
                log = OwnerDeviceTest.shell(String.format(java.util.Locale.ROOT,
                        "logcat -d -v epoch -T %.3f -s DecoderUsers:I", since));
                final Matcher f = line.matcher(log);
                if (f.find()) {
                    found = f;
                    break;
                }
            }
            assertNotNull("the icon's decode reached the service: " + log, found);
            assertTrue("the icon is the work profile's image, not the asking user's: " + found.group(),
                    Integer.parseInt(found.group(1)) == profile);
        } finally {
            Log.i(TAG, "profile " + profile + " removed: " + OwnerDeviceTest.shell("pm remove-user " + profile).trim());
        }
    }
}
