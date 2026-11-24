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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.widget.RemoteViews;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import de.diamaneos.decode.client.tests.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The images of an autofill service's views are made for the user of the autofill session, which can be a work
 * profile while its parent is in front. The work profile test needs a work profile with this package installed and
 * running, named with {@code -e workUser N}, and is skipped without it.
 */
@RunWith(AndroidJUnit4.class)
public class AutofillViewsDeviceTest {
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String mPackage = mContext.getPackageName();

    private static int workUser() {
        final String user = InstrumentationRegistry.getArguments().getString("workUser");
        assumeTrue("no work profile named (-e workUser N)", user != null);
        return Integer.parseInt(user);
    }

    private static FillResponse responseShowing(RemoteViews presentation) {
        return new FillResponse.Builder()
                .addDataset(new Dataset.Builder(presentation)
                        .setValue(new AutofillId(1), AutofillValue.forText("value"))
                        .build())
                .build();
    }

    private static <T> T onMain(java.util.concurrent.Callable<T> callable) {
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<RuntimeException> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                result.set(callable.call());
            } catch (RuntimeException e) {
                failure.set(e);
            } catch (Exception e) {
                failure.set(new RuntimeException(e));
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    // The pixels the image of the prepared views filled in with, or null after 15 s.
    private static Bitmap madeImage(RemoteViews prepared, Context shown) throws InterruptedException {
        final List<Icon> icons = RemoteViewsImages.icons(prepared);
        assertEquals(1, icons.size());
        final Drawable waiting = onMain(() -> icons.get(0).loadDrawable(shown));
        final long end = SystemClock.uptimeMillis() + 15_000;
        while (SystemClock.uptimeMillis() < end) {
            final Drawable inner = waiting instanceof DrawableWrapper
                    ? onMain(() -> ((DrawableWrapper) waiting).getDrawable()) : waiting;
            if (inner instanceof BitmapDrawable) {
                return ((BitmapDrawable) inner).getBitmap();
            }
            Thread.sleep(100);
        }
        return null;
    }

    @Test
    public void imagesOfAWorkProfileSession_comeFromTheWorkProfile() throws Exception {
        final int work = workUser();
        final RemoteViews presentation = new RemoteViews(mPackage, R.layout.vm_test_probe);
        presentation.setImageViewIcon(R.id.vm_image, Icon.createWithContentUri(UserImageProvider.IMAGE));
        final FillResponse response = responseShowing(presentation);
        final AutofillViews views = new AutofillViews(mContext, new Handler(Looper.getMainLooper()));

        final Context shown = onMain(() -> views.contextFor(response, mPackage, work, mContext));

        final Bitmap made = madeImage(presentation, shown);
        assertNotNull("the work profile's image was not made", made);
        assertEquals("opened in the wrong user", UserImageProvider.WORK,
                made.getPixel(made.getWidth() / 2, made.getHeight() / 2));
    }

    @Test
    public void aUriOfAnotherUser_isNotOpened() throws Exception {
        final int session = mContext.getUserId();
        final int other = session + 1;
        final Uri foreign = Uri.parse("content://" + other + "@" + ProbeProvider.AUTHORITY + "/icon/other-user");
        final RemoteViews presentation = new RemoteViews(mPackage, R.layout.vm_test_probe);
        presentation.setImageViewIcon(R.id.vm_image, Icon.createWithContentUri(foreign));
        final FillResponse response = responseShowing(presentation);
        final AutofillViews views = new AutofillViews(mContext, new Handler(Looper.getMainLooper()));
        final int opens = ProbeProvider.OPENS.get();

        assertNotNull(onMain(() -> views.contextFor(response, mPackage, session, mContext)));
        Thread.sleep(2_000);

        assertEquals(opens, ProbeProvider.OPENS.get());
        assertTrue(RemoteViewsImages.icons(presentation).stream()
                .noneMatch(icon -> icon.getType() == Icon.TYPE_URI));
    }

    @Test
    public void aSessionWithoutAKnownUser_showsNothing() {
        final RemoteViews presentation = new RemoteViews(mPackage, R.layout.vm_test_probe);
        final FillResponse response = responseShowing(presentation);
        final AutofillViews views = new AutofillViews(mContext, new Handler(Looper.getMainLooper()));

        assertThrows(IllegalArgumentException.class,
                () -> onMain(() -> views.contextFor(response, mPackage, UserHandle.USER_NULL, mContext)));
    }
}
