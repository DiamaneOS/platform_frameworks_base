package de.diamaneos.decode.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.Manifest;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ImageFileGates;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.diamaneos.cleancopies.CleanCopyGate;
import com.android.internal.diamaneos.cleancopies.CleanCopyKey;
import com.android.internal.diamaneos.cleancopies.CleanImageFile;

import de.diamaneos.decode.client.tests.R;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;

/** Real framework cache entries with controlled valid pixels for each user. */
@RunWith(AndroidJUnit4.class)
public class DrawableUsersDeviceTest {
    private static final int PERSONAL = 0;
    private final int WORK = Integer.parseInt(InstrumentationRegistry.getArguments().getString("workUser", "10"));
    private static final int RED = 0xffff0000;
    private static final int BLUE = 0xff0000ff;
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private static final class Source implements CleanCopyGate.Source {
        final Map<Integer, Integer> reads = new HashMap<>();

        @Override
        public CleanImageFile read(String key) {
            final int user = CleanCopyKey.userOf(key);
            reads.merge(user, 1, Integer::sum);
            final int color = user == PERSONAL ? RED : BLUE;
            final byte[] pixels = new byte[4 * 4 * 4];
            for (int i = 0; i < pixels.length; i += 4) {
                pixels[i] = (byte) (color >> 16);
                pixels[i + 1] = (byte) (color >> 8);
                pixels[i + 2] = (byte) color;
                pixels[i + 3] = (byte) 255;
            }
            return new CleanImageFile(key, "", 4, 4, 160, CleanImageFile.COLOR_SRGB, null, null, pixels);
        }

        @Override
        public CleanImageFile fallback(String apk, String file, int density, int w, int h, String res, int user) {
            return null;
        }

        @Override
        public void request(String key, String apk, String file, int density, int w, int h, String res, int user) {
        }
    }

    @After
    public void clear() {
        ImageFileGates.setProcessDefault(null);
        CleanCopyGate.setModeForTest(-1);
        InstrumentationRegistry.getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
    }

    private android.content.res.ImageFileGate gate(Source source) throws Exception {
        try {
            return (android.content.res.ImageFileGate) CleanCopyGate.class.getMethod("forTest", Context.class,
                    CleanCopyGate.Source.class, boolean.class).invoke(null, mContext, source, false);
        } catch (NoSuchMethodException oldPack) {
            return (android.content.res.ImageFileGate) CleanCopyGate.class.getMethod("forTest", Context.class,
                    CleanCopyGate.Source.class, boolean.class, boolean.class)
                    .invoke(null, mContext, source, false, false);
        }
    }

    private static int pixel(Drawable drawable) {
        assertNotNull(drawable);
        final Bitmap bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        drawable.setBounds(0, 0, 4, 4);
        drawable.draw(new Canvas(bitmap));
        return bitmap.getPixel(2, 2);
    }

    private void packageOrder(int first, int second, int res) throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
                Manifest.permission.INTERACT_ACROSS_USERS_FULL);
        final PackageManager pm = mContext.getPackageManager();
        final ApplicationInfo a = pm.getApplicationInfoAsUser(mContext.getPackageName(), 0, first);
        final ApplicationInfo b = pm.getApplicationInfoAsUser(mContext.getPackageName(), 0, second);
        final Source source = new Source();
        ImageFileGates.setProcessDefault(gate(source));
        final Drawable held = pm.getDrawable(a.packageName, res, a);
        assertEquals(first == PERSONAL ? RED : BLUE, pixel(held));
        final Drawable other = pm.getDrawable(b.packageName, res, b);
        assertEquals("second user must reach its own gate", second == PERSONAL ? RED : BLUE, pixel(other));
        final int before = source.reads.getOrDefault(second, 0);
        assertEquals(second == PERSONAL ? RED : BLUE, pixel(pm.getDrawable(b.packageName, res, b)));
        assertEquals("repeat load uses its own warm cache", before, (int) source.reads.getOrDefault(second, 0));
        assertEquals("the first drawable stays alive", first == PERSONAL ? RED : BLUE, pixel(held));
    }

    @Test
    public void packageManager_personalThenWork() throws Exception {
        packageOrder(PERSONAL, WORK, R.drawable.vm_test_orange);
    }

    @Test
    public void packageManager_workThenPersonal() throws Exception {
        packageOrder(WORK, PERSONAL, R.drawable.vm_test_blue);
    }

    @Test
    public void resources_bothOrdersAndWarmHits() throws Exception {
        for (int first : new int[] {PERSONAL, WORK}) {
            final Source source = new Source();
            final Resources res = mContext.createConfigurationContext(mContext.getResources().getConfiguration())
                    .getResources();
            res.setImageFileGate(gate(source));
            final int second = first == PERSONAL ? WORK : PERSONAL;
            final Drawable held = CleanCopyGate.iconLoad(first,
                    () -> res.getDrawable(R.drawable.vm_test_orange, null));
            final Drawable other = CleanCopyGate.iconLoad(second,
                    () -> res.getDrawable(R.drawable.vm_test_orange, null));
            assertEquals(first == PERSONAL ? RED : BLUE, pixel(held));
            assertEquals(second == PERSONAL ? RED : BLUE, pixel(other));
            final int before = source.reads.getOrDefault(second, 0);
            assertEquals(second == PERSONAL ? RED : BLUE, pixel(CleanCopyGate.iconLoad(second,
                    () -> res.getDrawable(R.drawable.vm_test_orange, null))));
            assertEquals(before, (int) source.reads.getOrDefault(second, 0));
        }
    }
}
