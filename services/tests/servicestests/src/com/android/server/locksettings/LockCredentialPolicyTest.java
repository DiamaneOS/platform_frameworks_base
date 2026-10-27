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
import com.android.internal.widget.ShortListPasswords;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

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

    private static int generatedPasswordStrength(String password) {
        return strength(LockscreenCredential.createPassword(password), /* generated= */ true);
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
        // Marking it as generated changes nothing: digits only are never a strong password.
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
    public void testShortListPasswordIsStrongOnlyWhenGenerated() {
        // uncle abandoned fox gem oat pry, and the first and last words of the list.
        for (String letters : new String[] {"uncabafoxgemoatpry", "aarzucaarzucaarzuc"}) {
            assertEquals(STRENGTH_STRONG, generatedPasswordStrength(letters));
            // Typed by a person, it is a short password like any other.
            assertEquals(STRENGTH_WEAKER, passwordStrength(letters));
        }
        // 17 and 19 letters, three letters that start no word, yo-yo, capitals, a space.
        for (String letters : new String[] {"uncabafoxgemoatpr", "uncabafoxgemoatprya",
                "uncabafoxgemoatqqq", "uncabafoxgemoatyo-", "UNCabafoxgemoatpry",
                "unc abafoxgemoatpr"}) {
            assertEquals(letters, STRENGTH_WEAKER, generatedPasswordStrength(letters));
        }
        // Fewer than five different letters: aardvark agency grape eggnog earlobe greyhound.
        assertEquals(STRENGTH_WEAKER, generatedPasswordStrength("aaragegraeggeargre"));
    }

    @Test
    public void testShortListPrefixesAreThoseOfTheList() throws Exception {
        // Settings' ShortListTest pins the same hash of the prefixes its word list has.
        final StringBuilder accepted = new StringBuilder();
        final char[] prefix = new char[3];
        for (int i = 0; i < 26 * 26 * 26; i++) {
            prefix[0] = (char) ('a' + i / (26 * 26));
            prefix[1] = (char) ('a' + i / 26 % 26);
            prefix[2] = (char) ('a' + i % 26);
            if (generatedPasswordStrength(new String(prefix) + "abafoxgemoatpry")
                    == STRENGTH_STRONG) {
                accepted.append(prefix);
            }
        }
        assertEquals(ShortListPasswords.PREFIX_COUNT * 3, accepted.length());
        assertEquals(1295, ShortListPasswords.PREFIX_COUNT);
        assertEquals("ef92078b878508cbd4c33129e107999979e5d8bc14705c3818a202709871f6d7",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(accepted.toString().getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    public void testGeneratedWords() {
        assertEquals(STRENGTH_STRONG,
                passwordStrength("unmoved skater daybed overripe pushcart"));
        assertEquals(STRENGTH_STRONG,
                passwordStrength("unmoved-skater-daybed-overripe-pushcart-tiling"));
    }
}
