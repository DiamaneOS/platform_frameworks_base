/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The window manager records the SHA-256 of each task snapshot image it writes in the snapshot's proto, and the file
 * is the system server's own: owner system, mode 0600, one name, label system_data_file (the checks
 * com.android.server.wm.SnapshotImageFiles makes before it decodes a snapshot without the VM). A task of this app is
 * started and sent to the background so its snapshot is written. Reads the files as root (debuggable builds).
 */
@RunWith(AndroidJUnit4.class)
public class SnapshotFilesDeviceTest {
    @Test
    public void aWrittenSnapshotHasItsHashRecordedAndIsTheServersOwnFile() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final int color = Color.rgb(90, (int) (SystemClock.uptimeMillis() % 250), 30);
        context.startActivity(new Intent(context, TaskIconActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .putExtra(TaskIconActivity.EXTRA_COLOR, color));
        int taskId = -1;
        final long found = SystemClock.uptimeMillis() + 10_000;
        while (taskId < 0 && SystemClock.uptimeMillis() < found) {
            SystemClock.sleep(300);
            for (ActivityManager.RecentTaskInfo task : ActivityTaskManager.getService()
                    .getRecentTasks(20, 0, 0).getList()) {
                if (task.baseActivity != null
                        && TaskIconActivity.class.getName().equals(task.baseActivity.getClassName())) {
                    taskId = task.taskId;
                    break;
                }
            }
        }
        assertTrue("the test task was not found", taskId >= 0);
        SystemClock.sleep(1_000);
        DeviceShell.run("input keyevent KEYCODE_HOME");
        // The files are in a random subdirectory of the user's snapshots directory. The commands use a glob, && and
        // quotes, so they go through a shell (DeviceShell): the plain test shell command runs without one.
        String image = "";
        String proto = "";
        String hash = "";
        final long written = SystemClock.uptimeMillis() + 20_000;
        while (SystemClock.uptimeMillis() < written) {
            SystemClock.sleep(500);
            final String listed = DeviceShell.run("su 0 sh -c 'ls /data/system_ce/0/snapshots/*/" + taskId
                    + "_reduced.jpg'").trim();
            if (!listed.matches("/data/system_ce/0/snapshots/[0-9a-f-]+/[0-9]+_reduced\\.jpg")) {
                continue;
            }
            image = listed;
            proto = listed.substring(0, listed.length() - "_reduced.jpg".length()) + ".proto";
            final String out = DeviceShell.run("su 0 sha256sum " + image).trim();
            if (out.matches("[0-9a-f]{64} .*")) {
                hash = out.substring(0, 64);
                if (DeviceShell.run("su 0 grep -q -F " + hash + " " + proto + " && echo RECORDED")
                        .contains("RECORDED")) {
                    break;
                }
            }
        }
        assertTrue("no snapshot image written for task " + taskId, hash.length() == 64);
        assertTrue("the image's SHA-256 is not in the snapshot's proto",
                DeviceShell.run("su 0 grep -q -F " + hash + " " + proto + " && echo RECORDED")
                        .contains("RECORDED"));
        final String stat = DeviceShell.run("su 0 stat -c '%u %a %h' " + image).trim();
        assertEquals("owner, mode and names of " + image, "1000 600 1", stat);
        final String label = DeviceShell.run("su 0 ls -Z " + image).trim();
        assertTrue("label of " + image + ": " + label, label.startsWith("u:object_r:system_data_file:s0 "));
        DeviceShell.run("am stack remove " + taskId);
    }
}
