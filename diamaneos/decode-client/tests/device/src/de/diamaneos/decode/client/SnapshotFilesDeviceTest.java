/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.SystemClock;
import android.util.Log;
import android.window.TaskSnapshot;
import android.window.TaskSnapshotManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The window manager records the SHA-256 of each task snapshot image it writes in the snapshot's proto, and the file
 * is the system server's own: owner system, mode 0600, one name, label system_data_file (the checks
 * com.android.server.wm.SnapshotImageFiles makes before it decodes a snapshot without the VM). A task of this app is
 * started and sent to the background so its snapshot is written. Reads the files as root (debuggable builds).
 *
 * <p>The task runs in a process of its own, which the test then ends, so the window manager drops the task's snapshot
 * from its cache and loads the reduced file from disk when asked: as written it is decoded in the system server, with
 * one byte changed it goes to the VM.
 * The route comes from SnapshotImageFiles' debug line (log.tag.SnapshotImageFiles DEBUG for the test). The window
 * manager takes no snapshot of a task that is not shown, so the test wakes the screen first.
 */
@RunWith(AndroidJUnit4.class)
public class SnapshotFilesDeviceTest {
    @Test
    public void aWrittenSnapshotHasItsHashRecordedAndIsTheServersOwnFile() throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DeviceShell.run("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard");
        final int color = Color.rgb(90, (int) (SystemClock.uptimeMillis() % 250), 30);
        context.startActivity(new Intent(context, SnapshotTaskActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .putExtra(TaskIconActivity.EXTRA_COLOR, color));
        int taskId = -1;
        final long found = SystemClock.uptimeMillis() + 10_000;
        while (taskId < 0 && SystemClock.uptimeMillis() < found) {
            SystemClock.sleep(300);
            for (ActivityManager.RecentTaskInfo task : ActivityTaskManager.getService()
                    .getRecentTasks(20, 0, 0).getList()) {
                if (task.baseActivity != null
                        && SnapshotTaskActivity.class.getName().equals(task.baseActivity.getClassName())) {
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
        DeviceShell.run("su 0 setprop log.tag.SnapshotImageFiles DEBUG");
        try {
            final String name = taskId + "_reduced.jpg";
            endTaskProcess(context);
            final int routes = routeLines(name);
            assertNotNull("the recorded snapshot did not load", loadLow(taskId));
            assertEquals("route of the recorded file", List.of(name + " decoded here, the recorded bytes"),
                    newLines(name, routes));
            final String flipped = DeviceShell.run("su 0 sh -c 'f=" + image + "; n=$(( $(stat -c %s $f) / 2 )); "
                    + "b=$(od -An -tu1 -j $n -N1 $f); printf \"\\\\$(printf %o $(( b ^ 1 )))\" "
                    + "| dd of=$f bs=1 seek=$n count=1 conv=notrunc 2>/dev/null && echo FLIPPED'");
            assertTrue("byte not changed: " + flipped, flipped.contains("FLIPPED"));
            assertEquals("owner, mode and names after the change", "1000 600 1",
                    DeviceShell.run("su 0 stat -c '%u %a %h' " + image).trim());
            final int before = routeLines(name);
            final boolean loaded = loadLow(taskId) != null;
            Log.i("SnapshotFiles", "changed byte, VM decode " + (loaded ? "gave a snapshot" : "gave none"));
            assertEquals("route of the changed file", List.of(name + " decoded in the VM"), newLines(name, before));
        } finally {
            DeviceShell.run("su 0 setprop log.tag.SnapshotImageFiles ''");
            DeviceShell.run("am stack remove " + taskId);
        }
    }

    // The task's process ends, so the window manager drops its cached snapshot (SnapshotCache.onAppDied).
    private static void endTaskProcess(Context context) throws Exception {
        final ActivityManager am = context.getSystemService(ActivityManager.class);
        int pid = -1;
        for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
            if (info.processName.equals(context.getPackageName() + ":snapshot")) pid = info.pid;
        }
        assertTrue("the task's process was not found", pid > 0);
        android.os.Process.killProcess(pid);
        final long end = SystemClock.uptimeMillis() + 5_000;
        boolean gone = false;
        while (!gone && SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(100);
            gone = true;
            for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
                if (info.pid == pid) gone = false;
            }
        }
        assertTrue("the task's process did not end", gone);
        SystemClock.sleep(500);
    }

    // The reduced snapshot, asked for with no capture time: with the task's cached snapshot dropped, the window
    // manager loads it from disk on each call (the low-resolution load is not cached).
    private static TaskSnapshot loadLow(int taskId) throws Exception {
        final android.app.UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        automation.adoptShellPermissionIdentity();
        try {
            final TaskSnapshot snapshot = ActivityTaskManager.getService().getTaskSnapshotManager()
                    .getTaskSnapshot(taskId, -1, TaskSnapshotManager.RESOLUTION_LOW);
            if (snapshot != null && snapshot.getHardwareBuffer() != null) snapshot.getHardwareBuffer().close();
            return snapshot;
        } finally {
            automation.dropShellPermissionIdentity();
        }
    }

    private static List<String> lines(String name) throws Exception {
        final List<String> found = new ArrayList<>();
        for (String line : DeviceShell.run("logcat -d -v raw -s SnapshotImageFiles:D").split("\n")) {
            if (line.trim().startsWith(name + " ")) found.add(line.trim());
        }
        return found;
    }

    private static int routeLines(String name) throws Exception {
        return lines(name).size();
    }

    private static List<String> newLines(String name, int before) throws Exception {
        final long end = SystemClock.uptimeMillis() + 3_000;
        List<String> all = lines(name);
        while (all.size() <= before && SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(100);
            all = lines(name);
        }
        return all.subList(Math.min(before, all.size()), all.size());
    }
}
