// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The DiamaneOS Project
package de.diamaneos.sensorspermissiontests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/** Reads synthetic archives using the real public parser. No fixture is installed. */
public final class SensorOptOutTests extends Instrumentation {
    private static final String SENSOR = "android.permission.OTHER_SENSORS";
    private static final String[] CASES = {
        "absent", "false", "wrong_type", "true", "explicit", "no_code"
    };
    private static final boolean[] EXPECTED = {true, true, true, false, true, false};

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        int failed = 0;
        StringBuilder failures = new StringBuilder();
        for (int index = 0; index < CASES.length; index++) {
            String name = CASES[index];
            Bundle status = new Bundle();
            status.putString("class", getClass().getName());
            status.putString("test", name);
            status.putInt("numtests", CASES.length);
            status.putInt("current", index + 1);
            sendStatus(1, status);
            File file = new File(getTargetContext().getCacheDir(), name + ".apk");
            try {
                try (InputStream input = getContext().getAssets().open(name + ".apk")) {
                    Files.copy(input, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                PackageInfo info = getTargetContext().getPackageManager().getPackageArchiveInfo(
                        file.getAbsolutePath(), PackageManager.GET_PERMISSIONS);
                if (info == null) throw new AssertionError("archive did not parse");
                boolean requested = info.requestedPermissions != null
                        && Arrays.asList(info.requestedPermissions).contains(SENSOR);
                if (requested != EXPECTED[index]) {
                    throw new AssertionError("sensor request differs from expected " + EXPECTED[index]);
                }
                sendStatus(0, status);
            } catch (Exception | AssertionError error) {
                failed++;
                String detail = name + ": " + error.getClass().getSimpleName();
                failures.append(detail).append('\n');
                status.putString("stack", detail);
                sendStatus(-2, status);
            } finally {
                file.delete();
            }
        }
        Bundle result = new Bundle();
        result.putInt("cases_total", CASES.length);
        result.putInt("cases_failed", failed);
        result.putString("failures", failures.toString());
        result.putString("stream", "Parser cases: " + CASES.length + ", failures: " + failed + "\n");
        finish(failed == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
}
