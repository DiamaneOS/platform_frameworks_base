// Copyright (C) 2026 DiamaneOS
package de.diamaneos.decode.client;

import static org.junit.Assert.*;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.regex.*;

@RunWith(AndroidJUnit4.class)
public class ArchiveUserDeviceTest {
    private static final String FIXTURE = "de.diamaneos.archive.icon.fixture";

    @Test public void archivedFallbackKeepsItsSelectedWorkUserAndPixels() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String own = context.getPackageName();
        AppOpsManager ops = context.getSystemService(AppOpsManager.class);
        int oldMode = ops.unsafeCheckOpRawNoThrow(AppOpsManager.OPSTR_ARCHIVE_ICON_OVERLAY,
                Process.myUid(), own);
        String made = OwnerDeviceTest.shell("pm create-user --profileOf 0 --managed archive-source-test");
        Matcher created = Pattern.compile("created user id (\\d+)").matcher(made);
        assertTrue(made, created.find());
        int profile = Integer.parseInt(created.group(1));
        boolean overlayChanged = false;
        try {
            assertTrue(OwnerDeviceTest.shell("am start-user -w " + profile).contains("Success"));
            assertTrue(OwnerDeviceTest.shell("pm install-existing --user " + profile + " " + FIXTURE)
                    .contains("installed"));
            assertTrue(OwnerDeviceTest.shell("pm install-existing --user " + profile + " com.android.shell")
                    .contains("installed"));
            String archived = OwnerDeviceTest.shell("su 2000 cmd package archive --user " + profile + " " + FIXTURE);
            assertTrue("the work user's archive state exists: " + archived, archived.contains("Success"));
            overlayChanged = true;
            OwnerDeviceTest.shell("cmd appops set --user 0 " + own + " ARCHIVE_ICON_OVERLAY 1");
            assertEquals(AppOpsManager.MODE_IGNORED, ops.unsafeCheckOpRawNoThrow(
                    AppOpsManager.OPSTR_ARCHIVE_ICON_OVERLAY, Process.myUid(), own));
            double since = System.currentTimeMillis() / 1000.0;
            Bitmap icon = null;
            String log = "";
            boolean dispatched = false;
            long end = SystemClock.uptimeMillis() + 25_000;
            Pattern request = Pattern.compile("request of user (\\d+) (?:shared|per-user) in VM start \\d+"
                    + ", .*, lane (?:\\?gate:uid" + Process.myUid() + "|\\d+/\\?gate:"
                    + Pattern.quote(own) + "@" + Process.myUid() + "/bytes)(?:,|$)");
            while (SystemClock.uptimeMillis() < end) {
                Bitmap result = AppGlobals.getPackageManager().getArchivedAppIcon(FIXTURE, UserHandle.of(0), own);
                if (result != null) icon = result;
                SystemClock.sleep(500);
                log = OwnerDeviceTest.shell(String.format(java.util.Locale.ROOT,
                        "logcat -d -v epoch -T %.3f -s DecoderUsers:I", since));
                Matcher m = request.matcher(log);
                while (m.find()) {
                    assertEquals("selected archive user at the service: " + m.group(), profile,
                            Integer.parseInt(m.group(1)));
                    dispatched = true;
                }
                if (icon != null && dispatched) break;
            }
            assertNotNull("the archived fallback returned pixels", icon);
            assertEquals("the fixture icon was rendered instead of a default", 0xff53d09b,
                    icon.getPixel(icon.getWidth() * 2 / 5, icon.getHeight() * 2 / 5));
            assertTrue("the work-user icon reached the guest: " + log, dispatched);
        } finally {
            if (overlayChanged) {
                OwnerDeviceTest.shell("cmd appops set --user 0 " + own + " ARCHIVE_ICON_OVERLAY " + oldMode);
            }
            OwnerDeviceTest.shell("pm remove-user " + profile);
        }
    }
}
