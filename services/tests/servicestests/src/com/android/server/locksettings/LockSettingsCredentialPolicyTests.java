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
import static com.android.internal.widget.LockCredentialPolicy.STRENGTH_UNKNOWN;
import static com.android.internal.widget.LockCredentialPolicy.STRENGTH_WEAKER;
import static com.android.internal.widget.LockDomain.Secondary;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_NONE;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PATTERN;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PIN;
import static com.android.server.locksettings.LockSettingsService.LEARNING_PERIOD_MS;
import static com.android.server.locksettings.LockSettingsService.LEARNING_PERIOD_STRONG_AUTH_TIMEOUT_MS;
import static com.android.server.locksettings.LockSettingsService.WEAKER_CREDENTIAL_RISK_ACCEPTANCE_DURATION_MS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.widget.LockscreenCredential;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;

/** atest FrameworksServicesTests:LockSettingsCredentialPolicyTests */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class LockSettingsCredentialPolicyTests extends BaseLockSettingsServiceTests {
    private static final String STRONG_PASSWORD = "cabin otter mule zipper frost";
    private static final String OTHER_STRONG_PASSWORD = "walnut-ferry-quilt-ember-plank";
    private static final String LEGACY_RISK_ACCEPTED_KEY =
            "de.diamaneos.credential.weaker_risk_accepted";
    private static final String LEARNING_REMAINING_KEY =
            "de.diamaneos.credential.learning_remaining_ms";
    private static final String GENERATED_PIN_KEY = "de.diamaneos.credential.generated_pin";
    private static final String LEGACY_STRENGTH_KEY = "de.diamaneos.credential.strength";
    private static final byte[] TOKEN = "some-high-entropy-secure-token".getBytes();

    @Before
    public void setUp() {
        mInjector.mIsLockCredentialPolicyEnabled = true;
        mService.initializeSyntheticPassword(PRIMARY_USER_ID);
        mService.initializeSyntheticPassword(MANAGED_PROFILE_USER_ID);
        mService.initializeSyntheticPassword(SECONDARY_USER_ID);
    }

    private void assertRefused(int userId, LockscreenCredential credential,
            LockscreenCredential savedCredential) {
        final int typeBefore = mService.getCredentialType(userId);
        assertThrows(IllegalStateException.class,
                () -> mService.setLockCredential(credential, savedCredential, userId));
        assertEquals(typeBefore, mService.getCredentialType(userId));
    }

    @Test
    public void testWeakerCredentialsNeedAcceptance() throws Exception {
        assertFalse(mService.isWeakerCredentialRiskAccepted(PRIMARY_USER_ID));

        assertRefused(PRIMARY_USER_ID, newPin("123456"), nonePassword());
        assertRefused(PRIMARY_USER_ID, newPin("83920174650291847365"), nonePassword());
        assertRefused(PRIMARY_USER_ID, newPattern("123654"), nonePassword());
        assertRefused(PRIMARY_USER_ID, newPassword("password"), nonePassword());
        assertRefused(PRIMARY_USER_ID, newPassword("83920174650291847365"), nonePassword());

        assertEquals(CREDENTIAL_TYPE_NONE, mService.getCredentialType(PRIMARY_USER_ID));
        assertEquals(STRENGTH_NONE, mService.getCredentialStrength(PRIMARY_USER_ID));
    }

    @Test
    public void testRefusalSaysWhyAndWhatWorks() throws Exception {
        final IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> mService.setLockCredential(newPin("123456"), nonePassword(),
                        PRIMARY_USER_ID));

        final String message = e.getMessage();
        assertTrue(message, message.contains("has not accepted the risk"));
        assertTrue(message, message.contains("at least 20 characters"));
        // Nothing of the refused credential.
        assertFalse(message, message.contains("123456"));
    }

    @Test
    public void testStrongPasswordNeedsNoAcceptance() throws Exception {
        final LockscreenCredential password = newPassword(STRONG_PASSWORD);
        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));

        assertEquals(CREDENTIAL_TYPE_PASSWORD, mService.getCredentialType(PRIMARY_USER_ID));
        assertEquals(STRENGTH_STRONG, mService.getCredentialStrength(PRIMARY_USER_ID));
        assertTrue(mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */)
                .isMatched());
    }

    @Test
    public void testWeakerCredentialAfterAcceptance() throws Exception {
        final LockscreenCredential pin = newPin("123456");

        mService.setWeakerCredentialRiskAccepted(true, PRIMARY_USER_ID);
        assertTrue(mService.isWeakerCredentialRiskAccepted(PRIMARY_USER_ID));

        assertTrue(mService.setLockCredential(pin, nonePassword(), PRIMARY_USER_ID));
        assertEquals(CREDENTIAL_TYPE_PIN, mService.getCredentialType(PRIMARY_USER_ID));
        assertEquals(STRENGTH_WEAKER, mService.getCredentialStrength(PRIMARY_USER_ID));
        // The acceptance was for this one credential.
        assertFalse(mService.isWeakerCredentialRiskAccepted(PRIMARY_USER_ID));
    }

    @Test
    public void testWeakerCredentialReplacesWeakerWithoutAcceptance() throws Exception {
        final LockscreenCredential pin = newPin("123456");
        final LockscreenCredential otherPin = newPin("654321");
        final LockscreenCredential pattern = newPattern("123654");
        final LockscreenCredential password = newPassword("password");
        mService.setWeakerCredentialRiskAccepted(true, PRIMARY_USER_ID);
        assertTrue(mService.setLockCredential(pin, nonePassword(), PRIMARY_USER_ID));

        assertTrue(mService.setLockCredential(otherPin, pin, PRIMARY_USER_ID));
        assertTrue(mService.setLockCredential(pattern, otherPin, PRIMARY_USER_ID));
        assertEquals(CREDENTIAL_TYPE_PATTERN, mService.getCredentialType(PRIMARY_USER_ID));
        assertTrue(mService.setLockCredential(password, pattern, PRIMARY_USER_ID));
        assertEquals(STRENGTH_WEAKER, mService.getCredentialStrength(PRIMARY_USER_ID));
        assertTrue(mService.setLockCredential(pin, password, PRIMARY_USER_ID));
        assertEquals(CREDENTIAL_TYPE_PIN, mService.getCredentialType(PRIMARY_USER_ID));
        assertFalse(mService.isWeakerCredentialRiskAccepted(PRIMARY_USER_ID));
    }

    @Test
    public void testWeakerPasswordIsKnownFromTheOldCredential() throws Exception {
        final LockscreenCredential password = newPassword("password");
        mService.setWeakerCredentialRiskAccepted(true, PRIMARY_USER_ID);
        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));
        // As after a restart: the class of the password is no longer in memory.
        mService.lockUser(PRIMARY_USER_ID);
        assertEquals(STRENGTH_UNKNOWN, mService.getCredentialStrength(PRIMARY_USER_ID));

        assertTrue(mService.setLockCredential(newPin("123456"), password, PRIMARY_USER_ID));
        assertEquals(CREDENTIAL_TYPE_PIN, mService.getCredentialType(PRIMARY_USER_ID));
    }

    @Test
    public void testWrongOldCredentialOfTheWeakerClassDoesNotHelp() throws Exception {
        final LockscreenCredential password = newPassword(STRONG_PASSWORD);
        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));

        // Claiming a weaker current credential gets past the policy but not past verification.
        assertFalse(mService.setLockCredential(newPin("123456"), newPassword("password"),
                PRIMARY_USER_ID));
        assertFalse(mService.setLockCredential(newPin("123456"), newPin("654321"),
                PRIMARY_USER_ID));
        assertEquals(CREDENTIAL_TYPE_PASSWORD, mService.getCredentialType(PRIMARY_USER_ID));
        assertTrue(mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */)
                .isMatched());
    }

    @Test
    public void testAcceptanceRunsOut() throws Exception {
        final Duration acceptedAt = Duration.ofHours(1);
        mInjector.setTimeSinceBoot(acceptedAt);
        mService.setWeakerCredentialRiskAccepted(true, PRIMARY_USER_ID);

        mInjector.setTimeSinceBoot(
                acceptedAt.plusMillis(WEAKER_CREDENTIAL_RISK_ACCEPTANCE_DURATION_MS - 1));
        assertTrue(mService.isWeakerCredentialRiskAccepted(PRIMARY_USER_ID));

        mInjector.setTimeSinceBoot(
                acceptedAt.plusMillis(WEAKER_CREDENTIAL_RISK_ACCEPTANCE_DURATION_MS));
        assertFalse(mService.isWeakerCredentialRiskAccepted(PRIMARY_USER_ID));
        assertRefused(PRIMARY_USER_ID, newPin("123456"), nonePassword());
    }

    @Test
    public void testAcceptanceIsNotStored() throws Exception {
        final LockscreenCredential password = newPassword("password");
        mService.setWeakerCredentialRiskAccepted(true, PRIMARY_USER_ID);
        assertNull(mStorage.getString(LEGACY_RISK_ACCEPTED_KEY, null, PRIMARY_USER_ID));

        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));
        assertNull(mStorage.getString(LEGACY_RISK_ACCEPTED_KEY, null, PRIMARY_USER_ID));
        assertNull(mStorage.getString(LEGACY_STRENGTH_KEY, null, PRIMARY_USER_ID));
    }

    private void assertSamePolicyStateStored(int userId, int otherUserId) {
        for (String key : new String[] {LEGACY_RISK_ACCEPTED_KEY, LEGACY_STRENGTH_KEY,
                GENERATED_PIN_KEY, LEARNING_REMAINING_KEY}) {
            assertEquals(key, mStorage.getString(key, null, userId),
                    mStorage.getString(key, null, otherUserId));
        }
    }

    @Test
    public void testWeakerAndStrongPasswordLookTheSameInStorage() throws Exception {
        final Duration setAt = Duration.ofHours(1);
        final Duration learningPeriod = Duration.ofMillis(LEARNING_PERIOD_MS);
        final LockscreenCredential strongPassword = newPassword(STRONG_PASSWORD);
        final LockscreenCredential weakPassword = newPassword("password");
        mInjector.setTimeSinceBoot(setAt);
        assertTrue(mService.setLockCredential(strongPassword, nonePassword(), PRIMARY_USER_ID));
        mService.setWeakerCredentialRiskAccepted(true, SECONDARY_USER_ID);
        assertTrue(mService.setLockCredential(weakPassword, nonePassword(), SECONDARY_USER_ID));

        // The learning time is stored for both, and nothing else.
        assertEquals(Long.toString(LEARNING_PERIOD_MS),
                mStorage.getString(LEARNING_REMAINING_KEY, null, SECONDARY_USER_ID));
        assertSamePolicyStateStored(PRIMARY_USER_ID, SECONDARY_USER_ID);

        // It is counted down for both alike.
        mInjector.setTimeSinceBoot(setAt.plusDays(3));
        assertTrue(mService.verifyCredential(strongPassword, PRIMARY_USER_ID, 0 /* flags */)
                .isMatched());
        assertTrue(mService.verifyCredential(weakPassword, SECONDARY_USER_ID, 0 /* flags */)
                .isMatched());
        assertEquals(Long.toString(LEARNING_PERIOD_MS - Duration.ofDays(3).toMillis()),
                mStorage.getString(LEARNING_REMAINING_KEY, null, SECONDARY_USER_ID));
        assertSamePolicyStateStored(PRIMARY_USER_ID, SECONDARY_USER_ID);

        // And it ends for both alike.
        mInjector.setTimeSinceBoot(setAt.plus(learningPeriod));
        assertTrue(mService.verifyCredential(strongPassword, PRIMARY_USER_ID, 0 /* flags */)
                .isMatched());
        assertTrue(mService.verifyCredential(weakPassword, SECONDARY_USER_ID, 0 /* flags */)
                .isMatched());
        assertNull(mStorage.getString(LEARNING_REMAINING_KEY, null, SECONDARY_USER_ID));
        assertSamePolicyStateStored(PRIMARY_USER_ID, SECONDARY_USER_ID);
    }

    @Test
    public void testWeakerPasswordIsNotAskedForMoreOften() throws Exception {
        final Duration setAt = Duration.ofHours(1);
        final LockscreenCredential password = newPassword("password");
        mInjector.setTimeSinceBoot(setAt);
        mService.setWeakerCredentialRiskAccepted(true, PRIMARY_USER_ID);
        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));
        assertEquals(0, mService.getLearningPeriodRemainingMillis(PRIMARY_USER_ID));

        mInjector.setTimeSinceBoot(setAt.plusDays(3));
        assertTrue(mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */)
                .isMatched());
        assertEquals(0, mService.getLearningPeriodRemainingMillis(PRIMARY_USER_ID));
        verify(mStrongAuth, never()).setStrongAuthTimeoutLimit(anyLong(), anyInt());
    }

    @Test
    public void testWeakerPasswordEndsTheLimitOfAStrongOne() throws Exception {
        final LockscreenCredential strongPassword = newPassword(STRONG_PASSWORD);
        final LockscreenCredential weakPassword = newPassword("password");
        assertTrue(mService.setLockCredential(strongPassword, nonePassword(), PRIMARY_USER_ID));
        mService.setWeakerCredentialRiskAccepted(true, PRIMARY_USER_ID);

        reset(mStrongAuth);
        assertTrue(mService.setLockCredential(weakPassword, strongPassword, PRIMARY_USER_ID));
        verify(mStrongAuth).setStrongAuthTimeoutLimit(0, PRIMARY_USER_ID);
        verify(mStrongAuth).refreshStrongAuthTimeout(PRIMARY_USER_ID);
        assertEquals(0, mService.getLearningPeriodRemainingMillis(PRIMARY_USER_ID));
    }

    @Test
    public void testNoLimitWhileThePasswordClassIsUnknown() throws Exception {
        final LockscreenCredential password = newPassword(STRONG_PASSWORD);
        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));
        final long handle = mLocalService.addEscrowToken(TOKEN, PRIMARY_USER_ID, null);
        assertTrue(mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */)
                .isMatched());

        // As after a restart: the password was not entered yet.
        mService.lockUser(PRIMARY_USER_ID);
        reset(mStrongAuth);
        assertEquals(0, mService.getLearningPeriodRemainingMillis(PRIMARY_USER_ID));
        assertTrue(mLocalService.unlockUserWithToken(handle, TOKEN, PRIMARY_USER_ID));
        verify(mStrongAuth, never()).setStrongAuthTimeoutLimit(anyLong(), anyInt());

        // The first entry of the password tells the class, and the limit is set.
        assertTrue(mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */)
                .isMatched());
        verify(mStrongAuth).setStrongAuthTimeoutLimit(LEARNING_PERIOD_STRONG_AUTH_TIMEOUT_MS,
                PRIMARY_USER_ID);
        assertTrue(mService.getLearningPeriodRemainingMillis(PRIMARY_USER_ID) > 0);
    }

    @Test
    public void testChosenPinAndPatternHaveNoLearningTimeStored() throws Exception {
        mService.setWeakerCredentialRiskAccepted(true, PRIMARY_USER_ID);
        assertTrue(mService.setLockCredential(newPin("123456"), nonePassword(),
                PRIMARY_USER_ID));
        mService.setWeakerCredentialRiskAccepted(true, SECONDARY_USER_ID);
        assertTrue(mService.setLockCredential(newPattern("123654"), nonePassword(),
                SECONDARY_USER_ID));

        assertNull(mStorage.getString(LEARNING_REMAINING_KEY, null, PRIMARY_USER_ID));
        assertNull(mStorage.getString(LEARNING_REMAINING_KEY, null, SECONDARY_USER_ID));
    }

    @Test
    public void testAcceptanceCanBeWithdrawn() throws Exception {
        mService.setWeakerCredentialRiskAccepted(true, PRIMARY_USER_ID);
        mService.setWeakerCredentialRiskAccepted(false, PRIMARY_USER_ID);

        assertFalse(mService.isWeakerCredentialRiskAccepted(PRIMARY_USER_ID));
        assertRefused(PRIMARY_USER_ID, newPin("123456"), nonePassword());
    }

    @Test
    public void testStrongToWeakerNeedsAcceptanceEachTime() throws Exception {
        final LockscreenCredential pin = newPin("123456");
        final LockscreenCredential password = newPassword(STRONG_PASSWORD);

        mService.setWeakerCredentialRiskAccepted(true, PRIMARY_USER_ID);
        assertTrue(mService.setLockCredential(pin, nonePassword(), PRIMARY_USER_ID));
        assertTrue(mService.setLockCredential(password, pin, PRIMARY_USER_ID));
        assertEquals(STRENGTH_STRONG, mService.getCredentialStrength(PRIMARY_USER_ID));

        assertRefused(PRIMARY_USER_ID, newPin("654321"), password);
        assertTrue(mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */)
                .isMatched());

        mService.setWeakerCredentialRiskAccepted(true, PRIMARY_USER_ID);
        assertTrue(mService.setLockCredential(pin, password, PRIMARY_USER_ID));
        assertTrue(mService.setLockCredential(password, pin, PRIMARY_USER_ID));
        assertRefused(PRIMARY_USER_ID, pin, password);
    }

    @Test
    public void testStrongCredentialDropsUnusedAcceptance() throws Exception {
        final LockscreenCredential password = newPassword(STRONG_PASSWORD);
        mService.setWeakerCredentialRiskAccepted(true, PRIMARY_USER_ID);
        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));

        assertFalse(mService.isWeakerCredentialRiskAccepted(PRIMARY_USER_ID));
        assertRefused(PRIMARY_USER_ID, newPin("123456"), password);
    }

    @Test
    public void testRemovingTheCredentialIsNotJudged() throws Exception {
        final LockscreenCredential password = newPassword(STRONG_PASSWORD);
        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));

        assertTrue(mService.setLockCredential(nonePassword(), password, PRIMARY_USER_ID));
        assertEquals(CREDENTIAL_TYPE_NONE, mService.getCredentialType(PRIMARY_USER_ID));
        assertEquals(STRENGTH_NONE, mService.getCredentialStrength(PRIMARY_USER_ID));

        // Going through no credential is no way around the acceptance.
        assertRefused(PRIMARY_USER_ID, newPin("123456"), nonePassword());
    }

    @Test
    public void testNoneToWeakerNeedsAcceptanceEachTime() throws Exception {
        final LockscreenCredential pin = newPin("123456");
        mService.setWeakerCredentialRiskAccepted(true, PRIMARY_USER_ID);
        assertTrue(mService.setLockCredential(pin, nonePassword(), PRIMARY_USER_ID));
        assertTrue(mService.setLockCredential(nonePassword(), pin, PRIMARY_USER_ID));

        assertRefused(PRIMARY_USER_ID, pin, nonePassword());

        mService.setWeakerCredentialRiskAccepted(true, PRIMARY_USER_ID);
        assertTrue(mService.setLockCredential(pin, nonePassword(), PRIMARY_USER_ID));
    }

    @Test
    public void testRemovingTheCredentialDropsUnusedAcceptance() throws Exception {
        final LockscreenCredential password = newPassword(STRONG_PASSWORD);
        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));
        mService.setWeakerCredentialRiskAccepted(true, PRIMARY_USER_ID);
        assertTrue(mService.setLockCredential(nonePassword(), password, PRIMARY_USER_ID));

        assertFalse(mService.isWeakerCredentialRiskAccepted(PRIMARY_USER_ID));
        assertRefused(PRIMARY_USER_ID, newPin("123456"), nonePassword());
    }

    @Test
    public void testAcceptanceIsPerUser() throws Exception {
        final LockscreenCredential pin = newPin("123456");
        mService.setWeakerCredentialRiskAccepted(true, PRIMARY_USER_ID);

        assertFalse(mService.isWeakerCredentialRiskAccepted(SECONDARY_USER_ID));
        assertRefused(SECONDARY_USER_ID, pin, nonePassword());

        mService.setWeakerCredentialRiskAccepted(true, SECONDARY_USER_ID);
        assertTrue(mService.setLockCredential(pin, nonePassword(), SECONDARY_USER_ID));
        assertEquals(STRENGTH_WEAKER, mService.getCredentialStrength(SECONDARY_USER_ID));
        assertEquals(STRENGTH_NONE, mService.getCredentialStrength(PRIMARY_USER_ID));
    }

    @Test
    public void testAcceptanceNeedsAnExistingUser() {
        assertThrows(IllegalArgumentException.class,
                () -> mService.setWeakerCredentialRiskAccepted(true, DOES_NOT_EXIST_USER_ID));
        assertFalse(mService.isWeakerCredentialRiskAccepted(DOES_NOT_EXIST_USER_ID));
    }

    @Test
    public void testPolicyStateIsNotWritableAsLockSetting() {
        assertThrows(SecurityException.class,
                () -> mService.setBoolean(LEGACY_RISK_ACCEPTED_KEY, true, PRIMARY_USER_ID));
        assertThrows(SecurityException.class,
                () -> mService.setLong(LEGACY_RISK_ACCEPTED_KEY, 1, PRIMARY_USER_ID));
        assertThrows(SecurityException.class,
                () -> mService.setString(LEGACY_RISK_ACCEPTED_KEY, "1", PRIMARY_USER_ID));
        assertThrows(SecurityException.class,
                () -> mService.setLong("de.diamaneos.credential.strength", STRENGTH_STRONG,
                        PRIMARY_USER_ID));

        assertFalse(mService.isWeakerCredentialRiskAccepted(PRIMARY_USER_ID));
        assertRefused(PRIMARY_USER_ID, newPin("123456"), nonePassword());
    }

    @Test
    public void testUnifiedProfilePasswordIsNotJudged() throws Exception {
        final LockscreenCredential password = newPassword(STRONG_PASSWORD);
        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);

        // The profile got its random password without an acceptance of its own.
        assertFalse(mService.isWeakerCredentialRiskAccepted(MANAGED_PROFILE_USER_ID));
        assertTrue(mService.isProfileWithTiedLock(MANAGED_PROFILE_USER_ID));
        assertEquals(CREDENTIAL_TYPE_PASSWORD,
                mService.getCredentialType(MANAGED_PROFILE_USER_ID));
    }

    @Test
    public void testSeparateProfileCredentialIsJudged() throws Exception {
        final LockscreenCredential password = newPassword(STRONG_PASSWORD);
        final LockscreenCredential profilePin = newPin("123456");
        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));

        // The parent's strong password does not cover a weaker lock of the profile's own.
        assertThrows(IllegalStateException.class,
                () -> mService.setLockCredential(profilePin, nonePassword(),
                        MANAGED_PROFILE_USER_ID));

        mService.setWeakerCredentialRiskAccepted(true, MANAGED_PROFILE_USER_ID);
        assertTrue(mService.setLockCredential(profilePin, nonePassword(),
                MANAGED_PROFILE_USER_ID));
        assertEquals(CREDENTIAL_TYPE_PIN, mService.getCredentialType(MANAGED_PROFILE_USER_ID));
        assertEquals(STRENGTH_WEAKER, mService.getCredentialStrength(MANAGED_PROFILE_USER_ID));
        assertEquals(STRENGTH_STRONG, mService.getCredentialStrength(PRIMARY_USER_ID));
    }

    @Test
    public void testBiometricSecondFactorPinIsNotJudged() throws Exception {
        final LockscreenCredential password = newPassword(STRONG_PASSWORD);
        final LockscreenCredential secondFactorPin = newPin("123456");
        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));

        assertTrue(mService.setLockCredential(secondFactorPin, password, Secondary,
                PRIMARY_USER_ID));
        assertEquals(CREDENTIAL_TYPE_PIN, mService.getCredentialType(PRIMARY_USER_ID, Secondary));
        assertEquals(STRENGTH_STRONG, mService.getCredentialStrength(PRIMARY_USER_ID));
    }

    @Test
    public void testTokenResetToWeakerCredentialNeedsAcceptance() throws Exception {
        final LockscreenCredential password = newPassword(STRONG_PASSWORD);
        final LockscreenCredential pin = newPin("123456");
        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));
        final long handle = mLocalService.addEscrowToken(TOKEN, PRIMARY_USER_ID, null);
        assertTrue(mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */)
                .isMatched());
        assertTrue(mLocalService.isEscrowTokenActive(handle, PRIMARY_USER_ID));

        assertFalse(mLocalService.setLockCredentialWithToken(pin, handle, TOKEN,
                PRIMARY_USER_ID));
        assertEquals(CREDENTIAL_TYPE_PASSWORD, mService.getCredentialType(PRIMARY_USER_ID));
        assertTrue(mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */)
                .isMatched());

        mService.setWeakerCredentialRiskAccepted(true, PRIMARY_USER_ID);
        assertTrue(mLocalService.setLockCredentialWithToken(pin, handle, TOKEN,
                PRIMARY_USER_ID));
        assertEquals(CREDENTIAL_TYPE_PIN, mService.getCredentialType(PRIMARY_USER_ID));
        assertEquals(STRENGTH_WEAKER, mService.getCredentialStrength(PRIMARY_USER_ID));
    }

    @Test
    public void testTokenResetToStrongPassword() throws Exception {
        final LockscreenCredential pin = newPin("123456");
        final LockscreenCredential password = newPassword(OTHER_STRONG_PASSWORD);
        mService.setWeakerCredentialRiskAccepted(true, PRIMARY_USER_ID);
        assertTrue(mService.setLockCredential(pin, nonePassword(), PRIMARY_USER_ID));
        final long handle = mLocalService.addEscrowToken(TOKEN, PRIMARY_USER_ID, null);
        assertTrue(mService.verifyCredential(pin, PRIMARY_USER_ID, 0 /* flags */).isMatched());

        assertTrue(mLocalService.setLockCredentialWithToken(password, handle, TOKEN,
                PRIMARY_USER_ID));
        assertEquals(STRENGTH_STRONG, mService.getCredentialStrength(PRIMARY_USER_ID));
        // And back to a PIN is refused: the strong password is what is in use now.
        assertFalse(mLocalService.setLockCredentialWithToken(pin, handle, TOKEN,
                PRIMARY_USER_ID));
    }

    @Test
    public void testTokenResetFromWeakerToWeaker() throws Exception {
        final LockscreenCredential pin = newPin("123456");
        mService.setWeakerCredentialRiskAccepted(true, PRIMARY_USER_ID);
        assertTrue(mService.setLockCredential(pin, nonePassword(), PRIMARY_USER_ID));
        final long handle = mLocalService.addEscrowToken(TOKEN, PRIMARY_USER_ID, null);
        assertTrue(mService.verifyCredential(pin, PRIMARY_USER_ID, 0 /* flags */).isMatched());
        // A PIN is told from the stored type, also when it was not entered since a restart.
        mService.lockUser(PRIMARY_USER_ID);

        assertTrue(mLocalService.setLockCredentialWithToken(newPattern("123654"), handle, TOKEN,
                PRIMARY_USER_ID));
        assertEquals(CREDENTIAL_TYPE_PATTERN, mService.getCredentialType(PRIMARY_USER_ID));
    }

    @Test
    public void testTokenResetFromWeakerPasswordNeedsItsClassInMemory() throws Exception {
        final LockscreenCredential password = newPassword("password");
        final LockscreenCredential pin = newPin("123456");
        mService.setWeakerCredentialRiskAccepted(true, PRIMARY_USER_ID);
        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));
        final long handle = mLocalService.addEscrowToken(TOKEN, PRIMARY_USER_ID, null);
        assertTrue(mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */)
                .isMatched());

        // As after a restart: nothing says that the password is a weaker one.
        mService.lockUser(PRIMARY_USER_ID);
        assertFalse(mLocalService.setLockCredentialWithToken(pin, handle, TOKEN,
                PRIMARY_USER_ID));
        assertEquals(CREDENTIAL_TYPE_PASSWORD, mService.getCredentialType(PRIMARY_USER_ID));

        // Once the password was entered, it does.
        assertTrue(mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */)
                .isMatched());
        assertTrue(mLocalService.setLockCredentialWithToken(pin, handle, TOKEN,
                PRIMARY_USER_ID));
        assertEquals(CREDENTIAL_TYPE_PIN, mService.getCredentialType(PRIMARY_USER_ID));
    }

    @Test
    public void testGeneratedPinIsStrong() throws Exception {
        final LockscreenCredential pin = mService.generateStrongPin(20, PRIMARY_USER_ID);
        assertTrue(pin.isPin());
        assertEquals(20, pin.size());
        for (byte digit : pin.getCredential()) {
            assertTrue(digit >= '0' && digit <= '9');
        }

        assertTrue(mService.setLockCredential(pin, nonePassword(), PRIMARY_USER_ID));
        assertEquals(CREDENTIAL_TYPE_PIN, mService.getCredentialType(PRIMARY_USER_ID));
        assertEquals(STRENGTH_STRONG, mService.getCredentialStrength(PRIMARY_USER_ID));
        assertTrue(mService.verifyCredential(pin, PRIMARY_USER_ID, 0 /* flags */).isMatched());

        // A chosen PIN is still refused, also in place of the generated one.
        assertFalse(mService.isWeakerCredentialRiskAccepted(PRIMARY_USER_ID));
        assertRefused(PRIMARY_USER_ID, newPin("83920174650291847365"), pin);
    }

    @Test
    public void testGeneratedPinsDiffer() throws Exception {
        final LockscreenCredential first = mService.generateStrongPin(32, PRIMARY_USER_ID);
        final LockscreenCredential second = mService.generateStrongPin(32, PRIMARY_USER_ID);
        assertEquals(32, first.size());
        assertFalse(first.equals(second));

        // Only the last one made for the user counts as generated.
        assertRefused(PRIMARY_USER_ID, first, nonePassword());
        assertTrue(mService.setLockCredential(second, nonePassword(), PRIMARY_USER_ID));
        assertEquals(STRENGTH_STRONG, mService.getCredentialStrength(PRIMARY_USER_ID));
    }

    @Test
    public void testChosenPinOfTheSameLengthIsRefused() throws Exception {
        mService.generateStrongPin(20, PRIMARY_USER_ID).zeroize();

        assertRefused(PRIMARY_USER_ID, newPin("83920174650291847365"), nonePassword());
    }

    @Test
    public void testGeneratedPinIsForOneUser() throws Exception {
        final LockscreenCredential pin = mService.generateStrongPin(20, PRIMARY_USER_ID);

        assertRefused(SECONDARY_USER_ID, pin, nonePassword());
        assertTrue(mService.setLockCredential(pin, nonePassword(), PRIMARY_USER_ID));
    }

    @Test
    public void testGeneratedPinIsForgottenWhenACredentialIsSet() throws Exception {
        final LockscreenCredential pin = mService.generateStrongPin(20, PRIMARY_USER_ID);
        final LockscreenCredential password = newPassword(STRONG_PASSWORD);
        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));

        assertRefused(PRIMARY_USER_ID, pin, password);
    }

    @Test
    public void testGeneratedPinAsPasswordIsWeaker() throws Exception {
        final LockscreenCredential pin = mService.generateStrongPin(20, PRIMARY_USER_ID);
        final LockscreenCredential password =
                LockscreenCredential.createPassword(new String(pin.getCredential()));

        assertRefused(PRIMARY_USER_ID, password, nonePassword());
    }

    @Test
    public void testGeneratedPinArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> mService.generateStrongPin(19, PRIMARY_USER_ID));
        assertThrows(IllegalArgumentException.class,
                () -> mService.generateStrongPin(129, PRIMARY_USER_ID));
        assertThrows(IllegalArgumentException.class,
                () -> mService.generateStrongPin(20, DOES_NOT_EXIST_USER_ID));
    }

    @Test
    public void testTokenResetToGeneratedPin() throws Exception {
        final LockscreenCredential password = newPassword(STRONG_PASSWORD);
        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));
        final long handle = mLocalService.addEscrowToken(TOKEN, PRIMARY_USER_ID, null);
        assertTrue(mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */)
                .isMatched());

        final LockscreenCredential pin = mService.generateStrongPin(20, PRIMARY_USER_ID);
        assertTrue(mLocalService.setLockCredentialWithToken(pin, handle, TOKEN,
                PRIMARY_USER_ID));
        assertEquals(STRENGTH_STRONG, mService.getCredentialStrength(PRIMARY_USER_ID));
    }

    @Test
    public void testStrongCredentialIsLearnedForTwoWeeks() throws Exception {
        final Duration setAt = Duration.ofHours(1);
        final Duration learningPeriod = Duration.ofMillis(LEARNING_PERIOD_MS);
        final LockscreenCredential password = newPassword(STRONG_PASSWORD);
        mInjector.setTimeSinceBoot(setAt);
        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));
        verify(mStrongAuth).setStrongAuthTimeoutLimit(LEARNING_PERIOD_STRONG_AUTH_TIMEOUT_MS,
                PRIMARY_USER_ID);
        verify(mStrongAuth).refreshStrongAuthTimeout(PRIMARY_USER_ID);

        // A day before the end the timeout is still limited.
        reset(mStrongAuth);
        mInjector.setTimeSinceBoot(setAt.plus(learningPeriod).minusDays(1));
        assertTrue(mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */)
                .isMatched());
        verify(mStrongAuth).setStrongAuthTimeoutLimit(LEARNING_PERIOD_STRONG_AUTH_TIMEOUT_MS,
                PRIMARY_USER_ID);

        // At the end the limit goes.
        reset(mStrongAuth);
        mInjector.setTimeSinceBoot(setAt.plus(learningPeriod));
        assertTrue(mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */)
                .isMatched());
        verify(mStrongAuth).setStrongAuthTimeoutLimit(0, PRIMARY_USER_ID);

        // Afterwards the limit is not touched again.
        reset(mStrongAuth);
        mInjector.setTimeSinceBoot(setAt.plus(learningPeriod).plusDays(1));
        assertTrue(mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */)
                .isMatched());
        verify(mStrongAuth, never()).setStrongAuthTimeoutLimit(anyLong(), anyInt());
    }

    @Test
    public void testLearningPeriodRemaining() throws Exception {
        final Duration setAt = Duration.ofHours(1);
        final LockscreenCredential password = newPassword(STRONG_PASSWORD);
        assertEquals(0, mService.getLearningPeriodRemainingMillis(PRIMARY_USER_ID));

        mInjector.setTimeSinceBoot(setAt);
        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));
        assertEquals(LEARNING_PERIOD_MS,
                mService.getLearningPeriodRemainingMillis(PRIMARY_USER_ID));
        assertEquals(0, mService.getLearningPeriodRemainingMillis(SECONDARY_USER_ID));

        // Time counts between entries of the credential too.
        mInjector.setTimeSinceBoot(setAt.plusDays(3));
        assertEquals(LEARNING_PERIOD_MS - Duration.ofDays(3).toMillis(),
                mService.getLearningPeriodRemainingMillis(PRIMARY_USER_ID));
        assertTrue(mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */)
                .isMatched());
        assertEquals(LEARNING_PERIOD_MS - Duration.ofDays(3).toMillis(),
                mService.getLearningPeriodRemainingMillis(PRIMARY_USER_ID));

        // And it is over when the time is up, before the next unlock notes that.
        mInjector.setTimeSinceBoot(setAt.plusDays(20));
        assertEquals(0, mService.getLearningPeriodRemainingMillis(PRIMARY_USER_ID));
        assertTrue(mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */)
                .isMatched());
        assertEquals(0, mService.getLearningPeriodRemainingMillis(PRIMARY_USER_ID));
    }

    @Test
    public void testEveryUnlockCountsTheLearningTime() throws Exception {
        final Duration setAt = Duration.ofHours(1);
        final Duration learningPeriod = Duration.ofMillis(LEARNING_PERIOD_MS);
        mInjector.setTimeSinceBoot(setAt);
        assertTrue(mService.setLockCredential(newPassword(STRONG_PASSWORD), nonePassword(),
                PRIMARY_USER_ID));
        mService.setWeakerCredentialRiskAccepted(true, SECONDARY_USER_ID);
        assertTrue(mService.setLockCredential(newPassword("password"), nonePassword(),
                SECONDARY_USER_ID));

        // An unlock without the credential, as with a fingerprint. A weaker password is entered
        // less often than a strong one, and the stored time must not show that.
        mInjector.setTimeSinceBoot(setAt.plusDays(3));
        mService.userPresent(PRIMARY_USER_ID);
        mService.userPresent(SECONDARY_USER_ID);
        flushHandlerTasks();
        assertEquals(Long.toString(LEARNING_PERIOD_MS - Duration.ofDays(3).toMillis()),
                mStorage.getString(LEARNING_REMAINING_KEY, null, SECONDARY_USER_ID));
        assertSamePolicyStateStored(PRIMARY_USER_ID, SECONDARY_USER_ID);

        // The period also ends at such an unlock, for both.
        reset(mStrongAuth);
        mInjector.setTimeSinceBoot(setAt.plus(learningPeriod));
        mService.userPresent(PRIMARY_USER_ID);
        mService.userPresent(SECONDARY_USER_ID);
        flushHandlerTasks();
        assertNull(mStorage.getString(LEARNING_REMAINING_KEY, null, PRIMARY_USER_ID));
        assertNull(mStorage.getString(LEARNING_REMAINING_KEY, null, SECONDARY_USER_ID));
        verify(mStrongAuth).setStrongAuthTimeoutLimit(0, PRIMARY_USER_ID);
    }

    @Test
    public void testWeakerCredentialHasNoLearningPeriodRemaining() throws Exception {
        final LockscreenCredential password = newPassword(STRONG_PASSWORD);
        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));
        mService.setWeakerCredentialRiskAccepted(true, PRIMARY_USER_ID);
        assertTrue(mService.setLockCredential(newPin("123456"), password, PRIMARY_USER_ID));

        assertEquals(0, mService.getLearningPeriodRemainingMillis(PRIMARY_USER_ID));
    }

    @Test
    public void testNewStrongCredentialIsLearnedAnew() throws Exception {
        final Duration setAt = Duration.ofHours(1);
        final Duration learningPeriod = Duration.ofMillis(LEARNING_PERIOD_MS);
        final LockscreenCredential password = newPassword(STRONG_PASSWORD);
        final LockscreenCredential newPassword = newPassword(OTHER_STRONG_PASSWORD);
        mInjector.setTimeSinceBoot(setAt);
        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));
        mInjector.setTimeSinceBoot(setAt.plusDays(10));
        assertTrue(mService.setLockCredential(newPassword, password, PRIMARY_USER_ID));

        // The first password's period would be over by now.
        reset(mStrongAuth);
        mInjector.setTimeSinceBoot(setAt.plusDays(10).plus(learningPeriod).minusDays(1));
        assertTrue(mService.verifyCredential(newPassword, PRIMARY_USER_ID, 0 /* flags */)
                .isMatched());
        verify(mStrongAuth).setStrongAuthTimeoutLimit(LEARNING_PERIOD_STRONG_AUTH_TIMEOUT_MS,
                PRIMARY_USER_ID);
    }

    @Test
    public void testGeneratedPinIsLearned() throws Exception {
        final LockscreenCredential pin = mService.generateStrongPin(20, PRIMARY_USER_ID);
        assertTrue(mService.setLockCredential(pin, nonePassword(), PRIMARY_USER_ID));

        verify(mStrongAuth).setStrongAuthTimeoutLimit(LEARNING_PERIOD_STRONG_AUTH_TIMEOUT_MS,
                PRIMARY_USER_ID);
    }

    @Test
    public void testWeakerCredentialIsNotLearned() throws Exception {
        final LockscreenCredential pin = newPin("123456");
        mService.setWeakerCredentialRiskAccepted(true, PRIMARY_USER_ID);
        assertTrue(mService.setLockCredential(pin, nonePassword(), PRIMARY_USER_ID));
        assertTrue(mService.verifyCredential(pin, PRIMARY_USER_ID, 0 /* flags */).isMatched());

        verify(mStrongAuth, never()).setStrongAuthTimeoutLimit(anyLong(), anyInt());
    }

    @Test
    public void testWeakerCredentialEndsLearningPeriod() throws Exception {
        final LockscreenCredential password = newPassword(STRONG_PASSWORD);
        final LockscreenCredential pin = newPin("123456");
        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));
        mService.setWeakerCredentialRiskAccepted(true, PRIMARY_USER_ID);

        reset(mStrongAuth);
        assertTrue(mService.setLockCredential(pin, password, PRIMARY_USER_ID));
        verify(mStrongAuth).setStrongAuthTimeoutLimit(0, PRIMARY_USER_ID);
        verify(mStrongAuth).refreshStrongAuthTimeout(PRIMARY_USER_ID);

        reset(mStrongAuth);
        assertTrue(mService.verifyCredential(pin, PRIMARY_USER_ID, 0 /* flags */).isMatched());
        verify(mStrongAuth, never()).setStrongAuthTimeoutLimit(anyLong(), anyInt());
    }

    @Test
    public void testRemovingTheCredentialEndsLearningPeriod() throws Exception {
        final LockscreenCredential password = newPassword(STRONG_PASSWORD);
        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));

        reset(mStrongAuth);
        assertTrue(mService.setLockCredential(nonePassword(), password, PRIMARY_USER_ID));
        verify(mStrongAuth).setStrongAuthTimeoutLimit(0, PRIMARY_USER_ID);
    }

    @Test
    public void testUnifiedProfilePasswordIsNotLearned() throws Exception {
        final LockscreenCredential password = newPassword(STRONG_PASSWORD);
        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);
        assertTrue(mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */)
                .isMatched());

        verify(mStrongAuth, never()).setStrongAuthTimeoutLimit(anyLong(),
                eq(MANAGED_PROFILE_USER_ID));
    }

    @Test
    public void testPasswordStrengthIsKnownOnceEntered() throws Exception {
        final LockscreenCredential strongPassword = newPassword(STRONG_PASSWORD);
        final LockscreenCredential weakPassword = newPassword("password");
        // Set without the service noting the class: what a restart leaves behind.
        mInjector.mIsLockCredentialPolicyEnabled = false;
        assertTrue(mService.setLockCredential(strongPassword, nonePassword(), PRIMARY_USER_ID));
        assertTrue(mService.setLockCredential(weakPassword, nonePassword(), SECONDARY_USER_ID));
        mInjector.mIsLockCredentialPolicyEnabled = true;
        assertEquals(STRENGTH_UNKNOWN, mService.getCredentialStrength(PRIMARY_USER_ID));
        assertEquals(STRENGTH_UNKNOWN, mService.getCredentialStrength(SECONDARY_USER_ID));

        assertTrue(mService.verifyCredential(strongPassword, PRIMARY_USER_ID, 0 /* flags */)
                .isMatched());
        assertEquals(STRENGTH_STRONG, mService.getCredentialStrength(PRIMARY_USER_ID));
        assertEquals(STRENGTH_UNKNOWN, mService.getCredentialStrength(SECONDARY_USER_ID));

        assertTrue(mService.verifyCredential(weakPassword, SECONDARY_USER_ID, 0 /* flags */)
                .isMatched());
        assertEquals(STRENGTH_WEAKER, mService.getCredentialStrength(SECONDARY_USER_ID));
    }

    @Test
    public void testWrongPasswordLeavesStrengthUnknown() throws Exception {
        final LockscreenCredential password = newPassword("password");
        mInjector.mIsLockCredentialPolicyEnabled = false;
        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));
        mInjector.mIsLockCredentialPolicyEnabled = true;

        assertFalse(mService.verifyCredential(newPassword(STRONG_PASSWORD), PRIMARY_USER_ID,
                0 /* flags */).isMatched());
        assertEquals(STRENGTH_UNKNOWN, mService.getCredentialStrength(PRIMARY_USER_ID));
    }

    @Test
    public void testPinAndPatternStrengthNeedNoEntry() throws Exception {
        mInjector.mIsLockCredentialPolicyEnabled = false;
        assertTrue(mService.setLockCredential(newPattern("123654"), nonePassword(),
                PRIMARY_USER_ID));
        assertTrue(mService.setLockCredential(newPin("123456"), nonePassword(),
                SECONDARY_USER_ID));
        mInjector.mIsLockCredentialPolicyEnabled = true;

        // The type is enough, and the type is stored anyway.
        assertEquals(STRENGTH_WEAKER, mService.getCredentialStrength(PRIMARY_USER_ID));
        assertEquals(STRENGTH_WEAKER, mService.getCredentialStrength(SECONDARY_USER_ID));
    }

    @Test
    public void testPasswordStrengthIsForgottenWhenTheUserIsLocked() throws Exception {
        final LockscreenCredential password = newPassword(STRONG_PASSWORD);
        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));
        assertEquals(STRENGTH_STRONG, mService.getCredentialStrength(PRIMARY_USER_ID));

        mService.lockUser(PRIMARY_USER_ID);
        assertEquals(STRENGTH_UNKNOWN, mService.getCredentialStrength(PRIMARY_USER_ID));

        assertTrue(mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */)
                .isMatched());
        assertEquals(STRENGTH_STRONG, mService.getCredentialStrength(PRIMARY_USER_ID));
    }

    @Test
    public void testGeneratedPinStaysStrongWithoutEntry() throws Exception {
        final LockscreenCredential pin = mService.generateStrongPin(20, PRIMARY_USER_ID);
        assertTrue(mService.setLockCredential(pin, nonePassword(), PRIMARY_USER_ID));

        mService.lockUser(PRIMARY_USER_ID);
        assertEquals(STRENGTH_STRONG, mService.getCredentialStrength(PRIMARY_USER_ID));

        assertTrue(mService.verifyCredential(pin, PRIMARY_USER_ID, 0 /* flags */).isMatched());
        assertEquals(STRENGTH_STRONG, mService.getCredentialStrength(PRIMARY_USER_ID));
    }

    @Test
    public void testOnlyTheGeneratedPinMarkIsStored() throws Exception {
        final LockscreenCredential password = newPassword(STRONG_PASSWORD);
        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));
        assertNull(mStorage.getString(LEGACY_STRENGTH_KEY, null, PRIMARY_USER_ID));
        assertNull(mStorage.getString(GENERATED_PIN_KEY, null, PRIMARY_USER_ID));

        final LockscreenCredential pin = mService.generateStrongPin(20, PRIMARY_USER_ID);
        assertTrue(mService.setLockCredential(pin, password, PRIMARY_USER_ID));
        assertTrue(mStorage.getBoolean(GENERATED_PIN_KEY, false, PRIMARY_USER_ID));
        assertNull(mStorage.getString(LEGACY_STRENGTH_KEY, null, PRIMARY_USER_ID));

        // The mark goes with the PIN it was made for.
        final LockscreenCredential chosenPin = newPin("83920174650291847365");
        mService.setWeakerCredentialRiskAccepted(true, PRIMARY_USER_ID);
        assertTrue(mService.setLockCredential(chosenPin, pin, PRIMARY_USER_ID));
        assertNull(mStorage.getString(GENERATED_PIN_KEY, null, PRIMARY_USER_ID));
        assertEquals(STRENGTH_WEAKER, mService.getCredentialStrength(PRIMARY_USER_ID));

        assertTrue(mService.verifyCredential(chosenPin, PRIMARY_USER_ID, 0 /* flags */)
                .isMatched());
        assertEquals(STRENGTH_WEAKER, mService.getCredentialStrength(PRIMARY_USER_ID));
    }

    @Test
    public void testGeneratedPinMarkIsNotWritableAsLockSetting() {
        assertThrows(SecurityException.class,
                () -> mService.setBoolean(GENERATED_PIN_KEY, true, PRIMARY_USER_ID));
    }

    @Test
    public void testStateStoredByEarlierCodeIsRemoved() throws Exception {
        mStorage.setInt(LEGACY_STRENGTH_KEY, STRENGTH_WEAKER, PRIMARY_USER_ID);
        mStorage.setBoolean(LEGACY_RISK_ACCEPTED_KEY, true, PRIMARY_USER_ID);
        mStorage.setBoolean(LEGACY_RISK_ACCEPTED_KEY, true, SECONDARY_USER_ID);

        mService.removeLegacyCredentialPolicyState();

        assertNull(mStorage.getString(LEGACY_STRENGTH_KEY, null, PRIMARY_USER_ID));
        assertNull(mStorage.getString(LEGACY_RISK_ACCEPTED_KEY, null, PRIMARY_USER_ID));
        assertNull(mStorage.getString(LEGACY_RISK_ACCEPTED_KEY, null, SECONDARY_USER_ID));
        // And it was never an acceptance to this code.
        assertFalse(mService.isWeakerCredentialRiskAccepted(PRIMARY_USER_ID));
        assertRefused(PRIMARY_USER_ID, newPin("123456"), nonePassword());
    }

    @Test
    public void testUnlockWithTokenDoesNotTellThePasswordStrength() throws Exception {
        final LockscreenCredential password = newPassword(STRONG_PASSWORD);
        assertTrue(mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID));
        final long handle = mLocalService.addEscrowToken(TOKEN, PRIMARY_USER_ID, null);
        assertTrue(mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */)
                .isMatched());
        mService.lockUser(PRIMARY_USER_ID);

        assertTrue(mLocalService.unlockUserWithToken(handle, TOKEN, PRIMARY_USER_ID));
        assertEquals(STRENGTH_UNKNOWN, mService.getCredentialStrength(PRIMARY_USER_ID));
    }
}
