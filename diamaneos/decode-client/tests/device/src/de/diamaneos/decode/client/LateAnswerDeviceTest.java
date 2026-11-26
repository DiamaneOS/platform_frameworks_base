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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import de.diamaneos.sanitizer.decode.IDecodeService;
import de.diamaneos.sanitizer.decode.Thumbnail;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * A reply the service gave after its caller left: the caller's give-up stops the reading between stripes, and the
 * answer still counts as a late one, so what was left out for it is asked for again (the recovery listeners run).
 * Needs the decode service.
 */
@RunWith(AndroidJUnit4.class)
public class LateAnswerDeviceTest {
    private static final long DEADLINE_MS = 300;
    private static final long SERVICE_MS = 800;

    @After
    public void asBefore() {
        Thread.interrupted();
    }

    private static String shell(String command) throws IOException {
        try (ParcelFileDescriptor out = InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(command);
                InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(out)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** An 8x8 reply in one stripe. */
    private static final class Reply extends IDecodeService.Default {
        final Thumbnail thumbnail = new Thumbnail();

        Reply() {
            thumbnail.status = IDecodeService.STATUS_OK;
            thumbnail.width = 8;
            thumbnail.height = 8;
            thumbnail.stripeRows = 8;
            thumbnail.handle = 7;
            thumbnail.gainmapParams = new float[0];
        }

        @Override
        public byte[] readStripe(long handle, int index) {
            final byte[] s = new byte[8 * 8 * 4];
            for (int i = 3; i < s.length; i += 4) {
                s[i] = (byte) 255;
            }
            return s;
        }
    }

    @Test
    public void anAnswerAfterTheCallerLeft_isALateAnswer() throws Exception {
        assumeTrue("no decode service here", shell("pm path de.diamaneos.sanitizer").contains("package:"));
        final DecodeClient client = new DecodeClient(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        final Reply reply = new Reply();
        final int before = VmDecode.lateAnswerRounds();
        final int calls = VmDecode.lateCallsForTest();
        try {
            client.call(IDecodeService.PRIORITY_USER_WAITING, SystemClock.uptimeMillis() + DEADLINE_MS,
                    "late.answer.test", service -> {
                        // A call into the service runs on after its caller left: an interrupt cannot stop it.
                        SystemClock.sleep(SERVICE_MS);
                        return ReplyPixels.thumbnail(reply, reply.thumbnail, 8, 8);
                    });
            fail("answered before the caller's deadline");
        } catch (VmDecode.UnavailableException e) {
            // the caller left at its deadline
        }
        final long end = SystemClock.uptimeMillis() + SERVICE_MS + LateRuns.MAX_DELAY_MS + LateRuns.GAP_MS + 2_000;
        while ((VmDecode.lateCallsForTest() == calls || VmDecode.lateAnswerRounds() == before)
                && SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(100);
        }
        // Counted as a late answer of its call (a rebinding also runs the listeners, so the rounds alone prove
        // nothing), and the recovery listeners ran.
        assertTrue("the reply was not counted as a late answer", VmDecode.lateCallsForTest() > calls);
        assertTrue("the late answer ran no recovery", VmDecode.lateAnswerRounds() > before);
    }
}
