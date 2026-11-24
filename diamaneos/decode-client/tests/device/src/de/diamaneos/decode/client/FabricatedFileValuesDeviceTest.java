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
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.om.FabricatedOverlay;
import android.content.om.OverlayIdentifier;
import android.content.om.OverlayManager;
import android.content.om.OverlayManagerTransaction;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.util.TypedValue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.function.BiConsumer;

/**
 * The overlay manager takes file values of fabricated overlays from the system uid only (FabricatedFileValues), so
 * idmap2d never reads a nine-patch an app made. This platform-signed app (not the system uid) owns and targets its
 * overlay. Its plain value is taken, which shows the call reaches the service, and its valid nine-patch and plain
 * file values are refused, which stock Android takes.
 */
@RunWith(AndroidJUnit4.class)
public class FabricatedFileValuesDeviceTest {
    private static final String NAME = "vm_file_values";
    private static final String RESOURCE = "drawable/vm_test_red";

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final OverlayManager mOverlays = mContext.getSystemService(OverlayManager.class);

    @After
    public void tearDown() {
        try {
            mOverlays.commit(new OverlayManagerTransaction.Builder().unregisterFabricatedOverlay(
                    new OverlayIdentifier(mContext.getPackageName(), NAME)).build());
        } catch (RuntimeException e) {
            // Nothing was registered.
        }
    }

    private FabricatedOverlay overlay() {
        final String pkg = mContext.getPackageName();
        return new FabricatedOverlay.Builder(pkg, NAME, pkg).build();
    }

    private void commit(FabricatedOverlay overlay) {
        mOverlays.commit(new OverlayManagerTransaction.Builder().registerFabricatedOverlay(overlay).build());
    }

    /** A valid nine-patch source PNG: transparent edges with black stretch marks at the top and the left. */
    private ParcelFileDescriptor ninePatchFile() throws Exception {
        final Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.TRANSPARENT);
        for (int y = 1; y < 7; y++) {
            for (int x = 1; x < 7; x++) {
                bitmap.setPixel(x, y, 0xff336699);
            }
        }
        for (int i = 3; i < 5; i++) {
            bitmap.setPixel(i, 0, Color.BLACK);
            bitmap.setPixel(0, i, Color.BLACK);
        }
        final File file = new File(mContext.getCacheDir(), "vm_file_values.9.png");
        try (FileOutputStream out = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private void assertRefused(BiConsumer<FabricatedOverlay, ParcelFileDescriptor> set) throws Exception {
        try (ParcelFileDescriptor pfd = ninePatchFile()) {
            final FabricatedOverlay overlay = overlay();
            set.accept(overlay, pfd);
            commit(overlay);
            fail("the overlay manager took a file value from uid " + Process.myUid());
        } catch (SecurityException e) {
            // The service names the reason on debuggable builds only.
            if (Build.IS_DEBUGGABLE) {
                assertNotNull(e.getMessage());
                assertTrue(e.getMessage(), e.getMessage().contains("may not set file values"));
            }
        }
    }

    @Test
    public void plainValue_taken() {
        final FabricatedOverlay overlay = overlay();
        overlay.setResourceValue(RESOURCE, TypedValue.TYPE_INT_COLOR_ARGB8, Color.RED, null);
        commit(overlay);
    }

    @Test
    public void ninePatchValue_refused() throws Exception {
        assertRefused((overlay, pfd) -> overlay.setNinePatchResourceValue(RESOURCE, pfd, null));
    }

    @Test
    public void fileValue_refused() throws Exception {
        assertRefused((overlay, pfd) -> overlay.setResourceValue(RESOURCE, pfd, null));
    }
}
