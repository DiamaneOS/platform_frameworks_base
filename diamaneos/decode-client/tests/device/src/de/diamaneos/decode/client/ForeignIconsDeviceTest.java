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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import de.diamaneos.decode.client.tests.R;

import java.io.ByteArrayOutputStream;

/**
 * The helpers behind the wallet cards, the shortcut helper, screen capture, the selection toolbar and the tuner: no
 * image an app hands over is decoded here. Holds with or without the decode VM (the made images are checked in
 * {@link VmPathDeviceTest}).
 */
@RunWith(AndroidJUnit4.class)
public class ForeignIconsDeviceTest {
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String mPackage = mContext.getPackageName();

    // A valid 7 x 3 PNG: a 7 x 3 bitmap anywhere below can only come from a decode here.
    private static Icon oracleIcon() {
        final Bitmap bitmap = Bitmap.createBitmap(7, 3, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xff336699);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        final byte[] png = out.toByteArray();
        return Icon.createWithData(png, 0, png.length);
    }

    private static boolean isOracle(Drawable d) {
        if (!(d instanceof BitmapDrawable) || ((BitmapDrawable) d).getBitmap() == null) {
            return false;
        }
        final Bitmap b = ((BitmapDrawable) d).getBitmap();
        return b.getWidth() == 7 && b.getHeight() == 3;
    }

    @Test
    public void streamImage_keepsDisplayP3() {
        // What a <bitmap> in an app's XML gets of an image the VM made: the colour space survives the stream.
        final Bitmap p3 = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888, true,
                android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3));
        p3.eraseColor(0xff00ff00);
        final byte[] bytes = AppResourceImages.encode(p3);
        assertNotNull(bytes);
        final Bitmap back = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        assertNotNull(back);
        assertEquals(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3),
                back.getColorSpace());
    }

    @Test
    public void streamImage_keepsTheGainmap() {
        // An Ultra HDR photo the VM made keeps its gain map through the stream, and the caps count it.
        final Bitmap photo = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        photo.eraseColor(0xff336699);
        final Bitmap gain = Bitmap.createBitmap(4, 4, Bitmap.Config.ALPHA_8);
        gain.eraseColor(0x80000000);
        photo.setGainmap(new android.graphics.Gainmap(gain));
        assertEquals(64 + 4, VmIcons.pixels(photo));
        final byte[] bytes = AppResourceImages.encode(photo);
        assertNotNull(bytes);
        final Bitmap back = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        assertNotNull(back);
        assertTrue("the gain map was lost on the way", back.hasGainmap());
    }

    @Test
    public void loadOrNeutral_onTheMainThread_neverDecodesTheApp() {
        final Drawable[] shown = new Drawable[2];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            shown[0] = ForeignIcons.loadOrNeutral(mContext, oracleIcon(), mContext.getUserId(), mPackage, 64, null);
            // An app's PNG resource: through the image gate, never plain resources.
            shown[1] = ForeignIcons.loadOrNeutral(mContext,
                    Icon.createWithResource(mPackage, R.drawable.vm_test_red), mContext.getUserId(), mPackage, 64,
                    null);
        });
        assertNotNull(shown[0]);
        assertFalse("decoded on Android: " + shown[0], isOracle(shown[0]));
        assertNotNull(shown[1]);
    }
}
