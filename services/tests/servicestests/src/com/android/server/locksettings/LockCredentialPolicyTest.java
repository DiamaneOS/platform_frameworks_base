/*
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

import static com.android.internal.widget.LockCredentialPolicy.STRENGTH_NONE;
import static com.android.internal.widget.LockCredentialPolicy.STRENGTH_STRONG;
import static com.android.internal.widget.LockCredentialPolicy.STRENGTH_WEAKER;

import static org.junit.Assert.assertEquals;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.widget.LockCredentialPolicy;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockscreenCredential;

import org.junit.Test;
import org.junit.runner.RunWith;

/** atest FrameworksServicesTests:LockCredentialPolicyTest */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class LockCredentialPolicyTest {
    private static final String TWENTY_DIGITS = "83920174650291847365";

    private static int strength(LockscreenCredential credential, boolean generated) {
        try (credential) {
            return LockCredentialPolicy.getStrength(credential, generated);
        }
    }

    private static int passwordStrength(String password) {
        return strength(LockscreenCredential.createPassword(password), /* generated= */ false);
    }

    @Test
    public void testNone() {
        assertEquals(STRENGTH_NONE, strength(LockscreenCredential.createNone(), false));
        assertEquals(STRENGTH_NONE, strength(LockscreenCredential.createNone(), true));
    }

    @Test
    public void testPatternIsWeaker() {
        final LockscreenCredential pattern = LockscreenCredential.createPattern(
                LockPatternUtils.byteArrayToPattern("123654789".getBytes()));
        assertEquals(STRENGTH_WEAKER, strength(pattern, false));
    }

    @Test
    public void testChosenPinIsWeakerAtAnyLength() {
        assertEquals(STRENGTH_WEAKER, strength(LockscreenCredential.createPin("123456"), false));
        assertEquals(STRENGTH_WEAKER,
                strength(LockscreenCredential.createPin(TWENTY_DIGITS), false));
        assertEquals(STRENGTH_WEAKER,
                strength(LockscreenCredential.createPin(TWENTY_DIGITS + TWENTY_DIGITS), false));
    }

    @Test
    public void testGeneratedPinIsStrongFromTwentyDigits() {
        assertEquals(STRENGTH_STRONG,
                strength(LockscreenCredential.createPin(TWENTY_DIGITS), true));
        assertEquals(STRENGTH_WEAKER,
                strength(LockscreenCredential.createPin(TWENTY_DIGITS.substring(1)), true));
    }

    @Test
    public void testPasswordLength() {
        // 19 and 20 characters.
        assertEquals(STRENGTH_WEAKER, passwordStrength("cabin-otter-mule-zi"));
        assertEquals(STRENGTH_STRONG, passwordStrength("cabin-otter-mule-zip"));
        assertEquals(STRENGTH_WEAKER, passwordStrength("password"));
    }

    @Test
    public void testPasswordOfDigitsIsWeaker() {
        assertEquals(STRENGTH_WEAKER, passwordStrength(TWENTY_DIGITS));
        assertEquals(STRENGTH_WEAKER, passwordStrength(TWENTY_DIGITS + TWENTY_DIGITS));
        // Marking a password as generated changes nothing: only PINs are generated.
        assertEquals(STRENGTH_WEAKER,
                strength(LockscreenCredential.createPassword(TWENTY_DIGITS), true));
    }

    @Test
    public void testPasswordNeedsFiveDifferentCharacters() {
        assertEquals(STRENGTH_WEAKER, passwordStrength("aaaaaaaaaaaaaaaaaaaa"));
        assertEquals(STRENGTH_WEAKER, passwordStrength("abcdabcdabcdabcdabcd"));
        assertEquals(STRENGTH_STRONG, passwordStrength("abcdeabcdeabcdeabcde"));
    }

    @Test
    public void testGeneratedWords() {
        assertEquals(STRENGTH_STRONG,
                passwordStrength("unmoved skater daybed overripe pushcart"));
        assertEquals(STRENGTH_STRONG,
                passwordStrength("unmoved-skater-daybed-overripe-pushcart-tiling"));
    }
}
