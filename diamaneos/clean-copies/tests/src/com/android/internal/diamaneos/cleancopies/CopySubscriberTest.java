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

package com.android.internal.diamaneos.cleancopies;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CopySubscriberTest {
    @Test
    public void directServerWatchDoesNotNeedAnExternalItemGrant() {
        assertTrue(new CopyAudience.Subscriber(1000, 77).mayNotify(77, true, false));
    }

    @Test
    public void anotherSystemUidProcessCannotBorrowTheServerWatch() {
        assertFalse(new CopyAudience.Subscriber(1000, 78).mayNotify(77, true, false));
    }

    @Test
    public void aSecondarySystemUidCannotBorrowTheServerWatch() {
        assertFalse(new CopyAudience.Subscriber(1101000, 77).mayNotify(77, true, false));
    }

    @Test
    public void anExternalSubscriberNeedsCurrentAdmissionAndAnItemGrant() {
        final CopyAudience.Subscriber subscriber = new CopyAudience.Subscriber(12345, 78);
        assertFalse(subscriber.mayNotify(77, false, true));
        assertFalse(subscriber.mayNotify(77, true, false));
        assertTrue(subscriber.mayNotify(77, true, true));
    }

    @Test
    public void serverDeliveryStillRechecksAdmission() {
        assertFalse(new CopyAudience.Subscriber(1000, 77).mayNotify(77, false, false));
    }
}
