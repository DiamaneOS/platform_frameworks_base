/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.android.internal.widget.LockDomain.Primary;
import static com.android.internal.widget.LockDomain.Secondary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.content.pm.UserInfo;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.internal.widget.LockDomain;
import com.android.internal.widget.LockscreenCredential;
import com.android.server.locksettings.SyntheticPasswordManager.PasswordData;
import com.android.server.locksettings.SyntheticPasswordManager.SyntheticPassword;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import junitparams.JUnitParamsRunner;

@SmallTest
@Presubmit
@RunWith(JUnitParamsRunner.class)
public class SyntheticPasswordScryptTests extends BaseLockSettingsServiceTests {

    private boolean lskfGatekeeperHandleExists(int userId, LockDomain lockDomain)
            throws RemoteException {
        return mGateKeeperService.getSecureUserId(SyntheticPasswordManager.fakeUserId(
                userId, lockDomain)) != 0;
    }

    private void initSpAndSetCredential(int userId, LockscreenCredential credential)
            throws RemoteException {
        initSpAndSetCredential(userId, credential, null);
    }

    private void initSpAndSetCredential(int userId, LockscreenCredential primaryCredential,
            LockscreenCredential secondaryCredential) {
        mService.initializeSyntheticPassword(userId);
        List<UserInfo> profiles = mUserManager.getProfiles(userId);
        if (profiles != null) {
            // Ensure that any managed profiles have initialized SPs.
            profiles.stream()
                    .filter(userInfo -> userInfo.isManagedProfile())
                    .forEach(userInfo -> mService.initializeSyntheticPassword(userInfo.id));
        }
        assertTrue(mService.setLockCredential(primaryCredential, nonePassword(), userId));
        assertEquals(primaryCredential.getType(), mService.getCredentialType(userId));

        if (secondaryCredential != null) {
            assertTrue(mService.setLockCredential(secondaryCredential, primaryCredential, Secondary,
                    userId));
            assertEquals(secondaryCredential.getType(), mService.getCredentialType(userId,
                    Secondary));
        }
    }

    private PasswordData createTestPasswordData() {
        PasswordData data = new PasswordData();
        // For the unit test, the scrypt parameters have to be constant; the salt can't be random.
        data.scryptLogN = 11;
        data.scryptLogR = 3;
        data.scryptLogP = 1;
        data.pinLength = 5;
        data.salt = "abcdefghijklmnop".getBytes();
        return data;
    }

    // The name of the file that holds a protector's PasswordData.
    private static final String PASSWORD_DATA_NAME = "pwd";

    private void assertStoredScryptParams(int userId, long protectorId, int expectedLogN,
            int expectedLogR, int expectedLogP) {
        assertTrue(mSpManager.hasPasswordData(protectorId, userId));
        PasswordData data = PasswordData.fromBytes(
                mStorage.readSyntheticPasswordState(userId, protectorId, PASSWORD_DATA_NAME));
        assertEquals(expectedLogN, data.scryptLogN);
        assertEquals(expectedLogR, data.scryptLogR);
        assertEquals(expectedLogP, data.scryptLogP);
    }

    private void assertLastScryptParams(int expectedLogN, int expectedLogR, int expectedLogP) {
        assertEquals(1 << expectedLogN, mSpManager.mLastScryptN);
        assertEquals(1 << expectedLogR, mSpManager.mLastScryptR);
        assertEquals(1 << expectedLogP, mSpManager.mLastScryptP);
    }

    // Sets the given credential as the user's primary LSKF and checks that its protector stores
    // the stronger scrypt parameters and that verification uses them.
    private void checkPrimaryLskfUsesStrongScryptParams(int userId, LockscreenCredential credential)
            throws RemoteException {
        initSpAndSetCredential(userId, credential);
        long protectorId = mService.getCurrentLskfBasedProtectorId(userId);
        assertStoredScryptParams(userId, protectorId, 18, 3, 0);

        final int callsBefore = mSpManager.mScryptCalls.size();
        assertTrue(mService.verifyCredential(credential, userId, 0 /* flags */).isMatched());
        // The first stretch of a verification is that of the entered credential.  Later ones can
        // unlock a profile whose lock is tied to this user, which uses the fast parameters.
        assertTrue(mSpManager.mScryptCalls.size() > callsBefore);
        final int[] firstCall = mSpManager.mScryptCalls.get(callsBefore);
        assertEquals(1 << 18, firstCall[0]);
        assertEquals(1 << 3, firstCall[1]);
        assertEquals(1 << 0, firstCall[2]);
    }

    @Test
    public void testPrimaryPasswordUsesStrongScryptParams() throws RemoteException {
        checkPrimaryLskfUsesStrongScryptParams(PRIMARY_USER_ID, newPassword("password"));
    }

    @Test
    public void testPrimaryPinUsesStrongScryptParams() throws RemoteException {
        checkPrimaryLskfUsesStrongScryptParams(PRIMARY_USER_ID, newPin("123456"));
    }

    @Test
    public void testPrimaryPatternUsesStrongScryptParams() throws RemoteException {
        checkPrimaryLskfUsesStrongScryptParams(PRIMARY_USER_ID, newPattern("12369"));
    }

    @Test
    public void testSecondaryUserPasswordUsesStrongScryptParams() throws RemoteException {
        checkPrimaryLskfUsesStrongScryptParams(SECONDARY_USER_ID, newPassword("password"));
    }

    // Tests that a profile's own credential (separate challenge) is treated like any other user's
    // primary LSKF.
    @Test
    public void testSeparateProfileChallengeUsesStrongScryptParams() throws RemoteException {
        mService.initializeSyntheticPassword(PRIMARY_USER_ID);
        mService.initializeSyntheticPassword(MANAGED_PROFILE_USER_ID);

        mService.setLockCredential(newPassword("primary"), nonePassword(), PRIMARY_USER_ID);
        mService.setLockCredential(newPassword("profile"), nonePassword(), MANAGED_PROFILE_USER_ID);

        assertStoredScryptParams(MANAGED_PROFILE_USER_ID,
                mService.getCurrentLskfBasedProtectorId(MANAGED_PROFILE_USER_ID), 18, 3, 0);
    }

    // Tests that the random password of a profile with unified challenge keeps the fast scrypt
    // parameters, while the parent's own credential gets the stronger ones.
    @Test
    public void testUnifiedProfilePasswordKeepsFastScryptParams() throws RemoteException {
        mService.initializeSyntheticPassword(PRIMARY_USER_ID);
        mService.initializeSyntheticPassword(MANAGED_PROFILE_USER_ID);

        mService.setLockCredential(newPassword("password"), nonePassword(), PRIMARY_USER_ID);
        mService.setSeparateProfileChallengeEnabled(MANAGED_PROFILE_USER_ID, false, null);

        assertStoredScryptParams(MANAGED_PROFILE_USER_ID,
                mService.getCurrentLskfBasedProtectorId(MANAGED_PROFILE_USER_ID), 9, 3, 1);
        assertStoredScryptParams(PRIMARY_USER_ID,
                mService.getCurrentLskfBasedProtectorId(PRIMARY_USER_ID), 18, 3, 0);
    }

    // Tests that a caller can't set a credential that is flagged as a unified profile password,
    // which would get the fast scrypt parameters.  Only the service itself creates those.
    @Test
    public void testSetLockCredential_rejectsUnifiedProfilePasswordFromCaller()
            throws RemoteException {
        final LockscreenCredential password = newPassword("password");
        final LockscreenCredential flaggedPassword =
                LockscreenCredential.createUnifiedProfilePassword("user-chosen".getBytes(UTF_8));
        mService.initializeSyntheticPassword(PRIMARY_USER_ID);
        mService.initializeSyntheticPassword(MANAGED_PROFILE_USER_ID);
        mService.setLockCredential(password, nonePassword(), PRIMARY_USER_ID);
        final long protectorId = mService.getCurrentLskfBasedProtectorId(PRIMARY_USER_ID);
        final long profileProtectorId =
                mService.getCurrentLskfBasedProtectorId(MANAGED_PROFILE_USER_ID);

        assertThrows(IllegalArgumentException.class,
                () -> mService.setLockCredential(flaggedPassword, password, PRIMARY_USER_ID));
        assertThrows(IllegalArgumentException.class,
                () -> mService.setLockCredential(flaggedPassword, nonePassword(),
                        MANAGED_PROFILE_USER_ID));

        assertEquals(protectorId, mService.getCurrentLskfBasedProtectorId(PRIMARY_USER_ID));
        assertEquals(profileProtectorId,
                mService.getCurrentLskfBasedProtectorId(MANAGED_PROFILE_USER_ID));
        assertTrue(mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */).isMatched());
    }

    // Tests that the biometric second factor PIN keeps the fast scrypt parameters.
    @Test
    public void testBiometricSecondFactorPinKeepsFastScryptParams() throws RemoteException {
        final LockscreenCredential password = newPassword("password");
        final LockscreenCredential secondaryPin = newPin("123456");
        initSpAndSetCredential(PRIMARY_USER_ID, password, secondaryPin);

        assertStoredScryptParams(PRIMARY_USER_ID,
                mService.getCurrentLskfBasedProtectorId(PRIMARY_USER_ID, Secondary), 9, 3, 1);
        assertStoredScryptParams(PRIMARY_USER_ID,
                mService.getCurrentLskfBasedProtectorId(PRIMARY_USER_ID), 18, 3, 0);

        assertTrue(mService.verifyCredential(
                secondaryPin, Secondary, PRIMARY_USER_ID, 0 /* flags */).isMatched());
        assertLastScryptParams(9, 3, 1);
    }

    // Tests that duress credentials keep the fast scrypt parameters.
    @Test
    public void testDuressCredentialKeepsFastScryptParams() {
        final LockscreenCredential pin = newPin("123456");
        DuressCredential duressCredential = DuressCredential.create(pin, mSpManager);
        assertLastScryptParams(9, 3, 1);

        assertTrue(duressCredential.verify(mSpManager, pin));
        assertLastScryptParams(9, 3, 1);
        assertFalse(duressCredential.verify(mSpManager, newPin("654321")));
    }

    // Tests that a failed scrypt makes a duress check a non-match instead of throwing, and that a
    // duress credential can't be created from a failed scrypt.
    @Test
    public void testDuressCredential_scryptFailure() {
        final LockscreenCredential pin = newPin("123456");
        DuressCredential duressCredential = DuressCredential.create(pin, mSpManager);

        mSpManager.failScryptAfter(0);
        assertFalse(duressCredential.verify(mSpManager, pin));
        assertThrows(IllegalStateException.class, () -> DuressCredential.create(pin, mSpManager));
    }

    // Tests that all scrypt parameters that were ever written are still accepted.
    @Test
    public void testStretchLskf_acceptsAllParamsEverWritten() {
        final int[][] goodParams = {{11, 3, 1}, {9, 3, 1}, {18, 3, 0}};
        for (int[] params : goodParams) {
            final PasswordData data = createTestPasswordData();
            data.scryptLogN = (byte) params[0];
            data.scryptLogR = (byte) params[1];
            data.scryptLogP = (byte) params[2];
            assertNotNull(mSpManager.stretchLskf(newPin("12345"), data));
            assertLastScryptParams(params[0], params[1], params[2]);
        }
    }

    // Tests that out-of-range scrypt parameters, as a tampered PasswordData could contain, are
    // rejected before scrypt is run.
    @Test
    public void testStretchLskf_rejectsOutOfRangeParams() {
        final int[][] badParams = {
                {30, 3, 0}, // 1 TiB
                {19, 3, 0}, // twice the memory of the strongest parameters
                {18, 4, 0}, // likewise
                {18, 3, 1}, // twice the time of the strongest parameters
                {0, 3, 1},
                {-1, 3, 1},
                {9, -1, 1},
                {9, 3, -1},
                {127, 127, 127},
        };
        final int scryptCallCount = mSpManager.mScryptCallCount;
        for (int[] params : badParams) {
            final PasswordData data = createTestPasswordData();
            data.scryptLogN = (byte) params[0];
            data.scryptLogR = (byte) params[1];
            data.scryptLogP = (byte) params[2];
            assertThrows(IllegalArgumentException.class,
                    () -> mSpManager.stretchLskf(newPin("12345"), data));
            assertNull(mSpManager.stretchLskfOrNull(newPin("12345"), data));
        }
        assertEquals(scryptCallCount, mSpManager.mScryptCallCount);
    }

    // Tests that a failed scrypt is an error rather than a null result.
    @Test
    public void testStretchLskf_scryptFailure() {
        mSpManager.failScryptAfter(0);
        assertThrows(IllegalStateException.class,
                () -> mSpManager.stretchLskf(newPin("12345"), createTestPasswordData()));
        assertNull(mSpManager.stretchLskfOrNull(newPin("12345"), createTestPasswordData()));
    }

    // Tests that when scrypt fails while a protector is being created, nothing is enrolled in
    // Gatekeeper and nothing is written to the FRP block.
    @Test
    public void testCreateLskfBasedProtector_scryptFailure() throws RemoteException {
        final LockscreenCredential password = newPassword("password");
        SyntheticPassword sp = mSpManager.newSyntheticPassword(PRIMARY_USER_ID, Primary);

        mSpManager.failScryptAfter(0);
        assertThrows(IllegalStateException.class,
                () -> mSpManager.createLskfBasedProtector(mGateKeeperService, password, Primary,
                        sp, PRIMARY_USER_ID));

        assertFalse(lskfGatekeeperHandleExists(PRIMARY_USER_ID, Primary));
        verify(mStorage.mPersistentDataBlockManager, never()).setFrpCredentialHandle(any());
    }

    // Tests that when scrypt fails for the new credential during a credential change, the change
    // fails as a whole and the old credential keeps working.
    @Test
    public void testChangeCredential_scryptFailureKeepsOldCredential() throws RemoteException {
        final LockscreenCredential password = newPassword("password");
        final LockscreenCredential newPassword = newPassword("newpassword");
        initSpAndSetCredential(PRIMARY_USER_ID, password);
        final long protectorId = mService.getCurrentLskfBasedProtectorId(PRIMARY_USER_ID);

        // Let the verification of the old credential succeed, then fail.
        mSpManager.failScryptAfter(1);
        assertThrows(IllegalStateException.class,
                () -> mService.setLockCredential(newPassword, password, PRIMARY_USER_ID));
        mSpManager.clearScryptFailure();

        assertEquals(protectorId, mService.getCurrentLskfBasedProtectorId(PRIMARY_USER_ID));
        assertTrue(mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */).isMatched());
        assertFalse(
                mService.verifyCredential(newPassword, PRIMARY_USER_ID, 0 /* flags */).isMatched());
    }

    // Tests that when scrypt fails during verification, an error is returned and the credential
    // still verifies afterwards, i.e. it isn't remembered as a wrong guess.
    @Test
    public void testVerifyCredential_scryptFailure() throws RemoteException {
        final LockscreenCredential password = newPassword("password");
        initSpAndSetCredential(PRIMARY_USER_ID, password);

        mSpManager.failScryptAfter(0);
        assertTrue(
                mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */).isOtherError());
        mSpManager.clearScryptFailure();

        assertTrue(mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */).isMatched());
    }

    // Tests that a protector whose stored scrypt parameters were tampered with fails verification
    // without running scrypt.
    @Test
    public void testVerifyCredential_tamperedScryptParams() throws RemoteException {
        final LockscreenCredential password = newPassword("password");
        initSpAndSetCredential(PRIMARY_USER_ID, password);
        final long protectorId = mService.getCurrentLskfBasedProtectorId(PRIMARY_USER_ID);

        PasswordData data = PasswordData.fromBytes(
                mStorage.readSyntheticPasswordState(PRIMARY_USER_ID, protectorId,
                        PASSWORD_DATA_NAME));
        data.scryptLogN = 30;
        mStorage.writeSyntheticPasswordState(PRIMARY_USER_ID, protectorId, PASSWORD_DATA_NAME,
                data.toBytes());
        final int scryptCallCount = mSpManager.mScryptCallCount;

        assertTrue(
                mService.verifyCredential(password, PRIMARY_USER_ID, 0 /* flags */).isOtherError());
        assertEquals(scryptCallCount, mSpManager.mScryptCallCount);
    }
}
