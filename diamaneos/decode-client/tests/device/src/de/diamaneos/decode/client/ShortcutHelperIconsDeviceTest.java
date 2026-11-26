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
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.drawable.Icon;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The keyboard shortcut helper's icons: the focused app names their resource package, and they are charged to the
 * shortcut helper's own lane, never to that package.
 */
@RunWith(AndroidJUnit4.class)
public class ShortcutHelperIconsDeviceTest {
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test
    public void shortcutHelperIconsAreChargedToItsOwnLane() throws Exception {
        // The focused app names a resource package (here this test's): the icon is looked up there, charged to the
        // shortcut helper's lane.
        final Icon icon = Icon.createWithResource(mContext.getPackageName(),
                de.diamaneos.decode.client.tests.R.drawable.vm_test_red);
        ForeignIcons.chargedForTest(true);
        try {
            final CountDownLatch loaded = new CountDownLatch(1);
            ForeignIcons.loadAsync(mContext, icon, mContext.getUserId(), ForeignIcons.SHORTCUT_HELPER, 64,
                    Runnable::run, d -> loaded.countDown());
            assertTrue(loaded.await(10, TimeUnit.SECONDS));
            final List<String> charged = ForeignIcons.chargedForTest(true);
            assertEquals("charged once, to the shortcut helper", List.of(ForeignIcons.SHORTCUT_HELPER), charged);
            assertFalse(charged.contains(mContext.getPackageName()));
            assertTrue("a lane of no app of its own", ForeignIcons.SHORTCUT_HELPER.startsWith("?"));
        } finally {
            ForeignIcons.chargedForTest(false);
        }
    }
}
