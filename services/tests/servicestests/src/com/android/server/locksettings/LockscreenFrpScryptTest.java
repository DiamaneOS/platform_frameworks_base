/*
 * Copyright (C) 2019 The Android Open Source Project
 * Copyright (C) 2026 The DiamaneOS Project
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

package com.android.server.locksettings;

import static com.android.internal.widget.LockPatternUtils.USER_FRP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.admin.DevicePolicyManager;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.locksettings.LockSettingsStorage.PersistentData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** The scrypt parameters of the Factory Reset Protection (FRP) credential. */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class LockscreenFrpScryptTest extends BaseLockSettingsServiceTests {

    @Before
    public void setUp() throws Exception {
        // FRP credential can only be verified prior to provisioning
        setDeviceProvisioned(false);

        mService.initializeSyntheticPassword(PRIMARY_USER_ID);
    }

    // The FRP block carries the scrypt parameters of the protector it was copied from, and they are
    // range-checked like a protector's own when the FRP credential is verified.
    @Test
    public void testFrpCredential_tamperedScryptParams() {
        mService.setLockCredential(newPin("1234"), nonePassword(), PRIMARY_USER_ID);
        PersistentData data = mStorage.readPersistentDataBlock();
        // Tweak the existing persistent data to make it ask for 1 TiB of scrypt memory
        assertEquals(18, data.payload[4]);
        data.payload[4] = 30;
        mStorage.writePersistentDataBlock(data.type, data.userId,
                DevicePolicyManager.PASSWORD_QUALITY_NUMERIC, data.payload);

        assertTrue(
                mService.verifyCredential(newPin("1234"), USER_FRP, 0 /* flags */).isOtherError());
    }
}
