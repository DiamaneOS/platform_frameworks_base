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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.os.UserHandle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import de.diamaneos.decode.client.tests.R;
import de.diamaneos.sanitizer.decode.AppImageFiles;
import de.diamaneos.sanitizer.decode.AppImagesRequest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * The clean icon cache's warm-up list (IDecodeService.listAppImageFiles), resolved in the decode service's process:
 * the image files of a splash theme and an icon of this test APK, references followed through XML drawables
 * (a {@code <bitmap>}, a layer list), with this APK as their APK. Needs the decode service, which lets this test
 * package ask on debuggable builds only (the system server is the only caller on user builds).
 */
@RunWith(AndroidJUnit4.class)
public class AppImageFilesDeviceTest {
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private static <T> T onThread(Callable<T> callable) throws Exception {
        final FutureTask<T> task = new FutureTask<>(callable);
        new Thread(task, "list-test").start();
        return task.get(30, TimeUnit.SECONDS);
    }

    private AppImagesRequest request(String packageName) {
        final AppImagesRequest request = new AppImagesRequest();
        request.packageName = packageName;
        request.userId = UserHandle.myUserId();
        request.themes = new int[] {R.style.VmTestSplash};
        request.icons = new int[] {R.drawable.vm_test_layers};
        request.densities = new int[] {480};
        return request;
    }

    @Test
    public void splashAndIcon_filesListed_throughXml() throws Exception {
        final AppImageFiles files = onThread(() -> VmDecode.listAppImageFiles(mContext,
                request(mContext.getPackageName()), VmDecode.deadlineIn(20_000)));
        final Set<String> names = new HashSet<>();
        for (int i = 0; i < files.files.length; i++) {
            if (!mContext.getApplicationInfo().sourceDir.equals(files.apkPaths[i])) {
                continue; // The framework's own files (a parent theme's), which the system server skips.
            }
            final String file = files.files[i];
            names.add(file.substring(file.lastIndexOf('/') + 1));
            assertTrue(files.resources[i], files.resources[i].startsWith(
                    mContext.getPackageName() + ":drawable/vm_test_"));
        }
        // The splash icon (a PNG), the branding image (a <bitmap> around a PNG), the background and the icon (a
        // layer list naming one PNG and wrapping another).
        assertTrue(names.toString(), names.contains("vm_test_red.png"));
        assertTrue(names.toString(), names.contains("vm_test_green.png"));
        assertTrue(names.toString(), names.contains("vm_test_blue.png"));
    }

    /** An icon loaded at another density is listed with the density a load sees, as the gates key it. */
    @Test
    public void iconAtAnotherDensity_densityAsALoadSeesIt() throws Exception {
        final AppImagesRequest request = request(mContext.getPackageName());
        request.themes = new int[0];
        request.icons = new int[] {R.drawable.vm_test_dens};
        request.densities = new int[] {640};
        final AppImageFiles files = onThread(() -> VmDecode.listAppImageFiles(mContext, request,
                VmDecode.deadlineIn(20_000)));
        assertEquals(1, files.files.length);
        final int display = mContext.getResources().getDisplayMetrics().densityDpi;
        assertEquals(320 * display / 640, files.densities[0]);
    }

    @Test
    public void unknownApp_refused() throws Exception {
        try {
            onThread(() -> VmDecode.listAppImageFiles(mContext, request("de.diamaneos.no.such.app"),
                    VmDecode.deadlineIn(20_000)));
            fail("listed an app that is not installed");
        } catch (java.util.concurrent.ExecutionException e) {
            assertTrue(String.valueOf(e.getCause()), e.getCause() instanceof VmDecode.RejectedException);
        }
    }
}
