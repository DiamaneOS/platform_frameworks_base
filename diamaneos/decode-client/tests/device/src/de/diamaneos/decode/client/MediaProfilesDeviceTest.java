/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import android.content.Context;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Log;
import android.util.Size;
import androidx.test.platform.app.InstrumentationRegistry;
import com.android.internal.diamaneos.cleancopies.VmThumbnails;
import java.io.IOException;
import org.junit.Test;

/** Uses real granted media fixtures through the same thumbnail hook as Files. */
public class MediaProfilesDeviceTest {
    @Test public void suppliedMediaAndDocumentUseTheVmThumbnailPath() throws Exception {
        final Bundle args = InstrumentationRegistry.getArguments();
        final String media = args.getString("vmMedia");
        assumeTrue("Device proof supplies a granted media URI", media != null);
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final android.app.UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        automation.adoptShellPermissionIdentity("android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO",
                "android.permission.MANAGE_DOCUMENTS", "android.permission.INTERACT_ACROSS_USERS_FULL");
        VmThumbnails.setForcedForTest(true);
        try {
            load(context.getContentResolver(), Uri.parse(media), args.getString("sourceUser"));
            final String document = args.getString("vmDocument");
            if (document != null) {
                load(context.getContentResolver(), Uri.parse(document), args.getString("sourceUser"));
            }
            final int user = Integer.parseInt(args.getString("resolverUser", "-1"));
            if (user >= 0) {
                final ContentResolver resolver = context.createContextAsUser(UserHandle.of(user), 0)
                        .getContentResolver();
                load(resolver, android.content.ContentProvider.getUriWithoutUserId(Uri.parse(media)),
                        args.getString("sourceUser"));
            }
        } finally {
            VmThumbnails.setForcedForTest(false);
            automation.dropShellPermissionIdentity();
        }
    }

    private static void load(ContentResolver resolver, Uri uri, String sourceUser) throws IOException {
        final int made = VmThumbnails.madeForTest();
        final Bitmap bitmap = resolver.loadThumbnail(uri, new Size(128, 128), null);
        try {
            assertNotNull(bitmap);
            assertTrue(bitmap.getWidth() > 0 && bitmap.getHeight() > 0);
            assertEquals(made + 1, VmThumbnails.madeForTest());
            Log.i("MediaProfilesProof", "VM thumbnail authority=" + uri.getAuthority()
                    + " expectedSourceUser=" + sourceUser + " size=" + bitmap.getWidth() + "x"
                    + bitmap.getHeight());
        } finally { if (bitmap != null) bitmap.recycle(); }
    }
}
