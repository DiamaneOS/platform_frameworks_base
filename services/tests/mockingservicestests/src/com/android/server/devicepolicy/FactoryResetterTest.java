/*
 * Copyright (C) 2020 The Android Open Source Project
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

// TODO(b/225012970): should be moved to com.android.server
package com.android.server.devicepolicy;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.FactoryResetter.isFactoryResetting;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.app.admin.DevicePolicySafetyChecker;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.os.RecoverySystem;
import android.os.UserManager;
import android.os.storage.DiskInfo;
import android.os.storage.StorageManager;
import android.platform.test.annotations.Presubmit;
import android.service.persistentdata.PersistentDataBlockManager;
import android.util.Log;

import com.android.internal.os.IResultReceiver;
import com.android.server.LocalServices;
import com.android.server.StorageManagerInternal;
import com.android.server.storage.ResetExternalStorage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Run it as {@code atest FrameworksMockingServicesTests:FactoryResetterTest}
 */
@Presubmit
public final class FactoryResetterTest {

    private static final String TAG = FactoryResetterTest.class.getSimpleName();

    private static final String REASON = "self-destruct";
    private static final String FIRST_CARD = "disk:179,0";
    private static final String SECOND_CARD = "disk:8,0";
    private final DiskInfo mFirstDisk = new DiskInfo(FIRST_CARD, DiskInfo.FLAG_ADOPTABLE);
    private final DiskInfo mSecondDisk = new DiskInfo(SECOND_CARD, DiskInfo.FLAG_ADOPTABLE);

    private MockitoSession mSession;

    private @Mock Context mContext;
    private @Mock StorageManager mSm;
    private @Mock StorageManagerInternal mStorageInternal;
    private @Mock PowerManager mPowerManager;
    private @Mock PowerManager.WakeLock mWakeLock;
    private @Mock PersistentDataBlockManager mPdbm;
    private @Mock UserManager mUm;
    private @Mock DevicePolicySafetyChecker mSafetyChecker;

    @Before
    public void startSession() {
        mSession = mockitoSession()
                .initMocks(this)
                .spyStatic(RecoverySystem.class)
                .spyStatic(LocalServices.class)
                .spyStatic(ResetExternalStorage.class)
                .strictness(Strictness.LENIENT)
                .startMocking();

        com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn(mStorageInternal)
                .when(() -> LocalServices.getService(StorageManagerInternal.class));
        when(mContext.getSystemService(any(Class.class))).thenAnswer((inv) -> {
            Log.d(TAG, "Mocking " + inv);
            Class<?> serviceClass = (Class<?>) inv.getArguments()[0];
            if (serviceClass.equals(PersistentDataBlockManager.class)) return mPdbm;
            if (serviceClass.equals(StorageManager.class)) return mSm;
            if (serviceClass.equals(PowerManager.class)) return mPowerManager;
            if (serviceClass.equals(UserManager.class)) return mUm;
            throw new IllegalArgumentException("Not expecting call for " + serviceClass);
        });
        when(mSm.getDisks()).thenReturn(List.of(mFirstDisk));
        when(mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ResetExternalStorage"))
                .thenReturn(mWakeLock);
        when(mWakeLock.isHeld()).thenReturn(true);

        doAnswer((inv) -> {
            Log.d(TAG, "Mocking " + inv);
            return null;
        }).when(() -> RecoverySystem.rebootWipeUserData(any(), anyBoolean(), any(),
                anyBoolean(), anyBoolean(), anyBoolean()));
    }

    @After
    public void finishSession() {
        if (mSession == null) {
            Log.w(TAG, "finishSession(): no session");
            return;
        }
        mSession.finishMocking();
    }

    @Test
    public void testFactoryResetBuilder_nullContext() throws Exception {
        assertThrows(NullPointerException.class, () -> FactoryResetter.newBuilder(null));
    }

    @Test
    public void testFactoryResetBuilder_nullReason() throws Exception {
        assertThrows(NullPointerException.class,
                () -> FactoryResetter.newBuilder(mContext).setReason(null));
    }

    @Test
    public void testFactoryReset_minimumArgs_noMasterClearPermission() throws Exception {
        revokeMasterClearPermission();
        allowFactoryReset();

        assertThrows(SecurityException.class,
                () -> FactoryResetter.newBuilder(mContext).build().factoryReset());

        verifyWipeAdoptableStorageNotCalled();
        verifyWipeFactoryResetProtectionNotCalled();
        verifyRebootWipeUserDataNotCalled();
    }

    @Test
    public void testFactoryReset_minimumArgs_withRestriction_notForced() throws Exception {
        disallowFactoryReset();

        assertThrows(SecurityException.class,
                () -> FactoryResetter.newBuilder(mContext).build().factoryReset());

        verifyWipeAdoptableStorageNotCalled();
        verifyWipeFactoryResetProtectionNotCalled();
        verifyRebootWipeUserDataNotCalled();
    }

    @Test
    public void testFactoryReset_minimumArgs_noRestriction_notForced() throws Exception {
        allowFactoryReset();

        FactoryResetter.newBuilder(mContext).build().factoryReset();

        verifyWipeAdoptableStorageNotCalled();
        verifyWipeFactoryResetProtectionNotCalled();
        verifyRebootWipeUserDataMinimumArgsCalled();
    }

    @Test
    public void testFactoryReset_minimumArgs_withRestriction_forced() throws Exception {
        disallowFactoryReset();

        FactoryResetter.newBuilder(mContext).setForce(true).build().factoryReset();

        verifyWipeAdoptableStorageNotCalled();
        verifyWipeFactoryResetProtectionNotCalled();
        verifyRebootWipeUserDataMinimumArgsButForceCalled();
    }

    @Test
    public void testFactoryReset_storageOnly() throws Exception {
        allowFactoryReset();

        boolean success = FactoryResetter.newBuilder(mContext)
                .setWipeAdoptableStorage(true).build()
                .factoryReset();

        assertThat(success).isTrue();
        assertThat(isFactoryResetting()).isTrue();
        verifyWipeAdoptableStorageCalled();
        verifyWipeFactoryResetProtectionNotCalled();
        verifyRebootWipeUserDataMinimumArgsCalled();
    }

    @Test
    public void policyResetReachesRecoveryWhileCardCallIsStillOpen() throws Exception {
        allowFactoryReset();
        var cardRelease = new CountDownLatch(1);
        var cardReturned = new CountDownLatch(1);
        when(mSm.getDisks()).thenReturn(List.of(mFirstDisk, mSecondDisk));
        org.mockito.Mockito.doAnswer(inv -> {
            try {
                cardRelease.await(8, TimeUnit.SECONDS);
            } finally {
                cardReturned.countDown();
            }
            return null;
        }).when(mStorageInternal).eraseAdoptableDiskForReset(eq(mFirstDisk), anyLong());
        doAnswer(inv -> {
            ResetExternalStorage.wipe(mContext, 1000);
            return null;
        }).when(() -> ResetExternalStorage.wipe(mContext));

        try {
            assertThat(FactoryResetter.newBuilder(mContext).setWipeAdoptableStorage(true)
                    .build().factoryReset()).isTrue();
            verifyRebootWipeUserDataMinimumArgsCalled();
            verifyWipeAdoptableStorageCalled();
            assertThat(cardReturned.getCount()).isEqualTo(1);
        } finally {
            cardRelease.countDown();
        }
        assertThat(cardReturned.await(5, TimeUnit.SECONDS)).isTrue();
        verify(mStorageInternal, never()).eraseAdoptableDiskForReset(eq(mSecondDisk), anyLong());
        verify(mWakeLock).acquire(2000);
        verify(mWakeLock).release();
    }

    @Test
    public void testFactoryReset_frpOnly() throws Exception {
        allowFactoryReset();

        boolean success = FactoryResetter.newBuilder(mContext)
                .setWipeFactoryResetProtection(true)
                .build().factoryReset();

        assertThat(success).isTrue();
        assertThat(isFactoryResetting()).isTrue();
        verifyWipeAdoptableStorageNotCalled();
        verifyWipeFactoryResetProtectionCalled();
        verifyRebootWipeUserDataMinimumArgsCalled();
    }

    @Test
    public void testFactoryReset_allArgs() throws Exception {
        allowFactoryReset();

        boolean success = FactoryResetter.newBuilder(mContext)
                .setReason(REASON)
                .setForce(true)
                .setShutdown(true)
                .setWipeEuicc(true)
                .setWipeAdoptableStorage(true)
                .setWipeFactoryResetProtection(true)
                .build().factoryReset();

        assertThat(success).isTrue();
        assertThat(isFactoryResetting()).isTrue();
        verifyWipeAdoptableStorageCalled();
        verifyWipeFactoryResetProtectionCalled();
        verifyRebootWipeUserDataAllArgsCalled();
    }

    @Test
    public void testFactoryReset_minimumArgs_safetyChecker_neverReplied() throws Exception {
        allowFactoryReset();

        boolean success = FactoryResetter.newBuilder(mContext)
                .setSafetyChecker(mSafetyChecker).build().factoryReset();

        assertThat(success).isFalse();
        assertThat(isFactoryResetting()).isTrue();
        verifyWipeAdoptableStorageNotCalled();
        verifyWipeFactoryResetProtectionNotCalled();
        verifyRebootWipeUserDataNotCalled();
    }

    @Test
    public void testFactoryReset_allArgs_safetyChecker_replied() throws Exception {
        allowFactoryReset();

        doAnswer((inv) -> {
            Log.d(TAG, "Mocking " + inv);
            IResultReceiver receiver = (IResultReceiver) inv.getArguments()[0];
            receiver.send(0, null);
            return null;
        }).when(mSafetyChecker).onFactoryReset(any());

        boolean success = FactoryResetter.newBuilder(mContext)
                .setSafetyChecker(mSafetyChecker)
                .setReason(REASON)
                .setForce(true)
                .setShutdown(true)
                .setWipeEuicc(true)
                .setWipeAdoptableStorage(true)
                .setWipeFactoryResetProtection(true)
                .build().factoryReset();

        assertThat(success).isFalse();
        assertThat(isFactoryResetting()).isTrue();
        verifyWipeAdoptableStorageCalled();
        verifyWipeFactoryResetProtectionCalled();
        verifyRebootWipeUserDataAllArgsCalled();
    }

    private void revokeMasterClearPermission() {
        when(mContext.checkCallingOrSelfPermission(android.Manifest.permission.MASTER_CLEAR))
                .thenReturn(PackageManager.PERMISSION_DENIED);
    }

    private void allowFactoryReset() {
        when(mUm.hasUserRestriction(UserManager.DISALLOW_FACTORY_RESET)).thenReturn(false);
    }

    private void disallowFactoryReset() {
        when(mUm.hasUserRestriction(UserManager.DISALLOW_FACTORY_RESET)).thenReturn(true);
    }

    private void verifyRebootWipeUserDataNotCalled() {
        verify(() -> RecoverySystem.rebootWipeUserData(any(), anyBoolean(), any(), anyBoolean(),
                anyBoolean()), never());
    }

    private void verifyRebootWipeUserDataMinimumArgsCalled() {
        verify(() -> RecoverySystem.rebootWipeUserData(mContext, /* shutdown= */ false,
                /* reason= */ null, /* force= */ false, /* wipeEuicc= */ false,
                /* keepMemtagMode= */ false));
    }

    private void verifyRebootWipeUserDataMinimumArgsButForceCalled() {
        verify(() -> RecoverySystem.rebootWipeUserData(mContext, /* shutdown= */ false,
                /* reason= */ null, /* force= */ true, /* wipeEuicc= */ false,
                /* keepMemtagMode= */ false));
    }

    private void verifyRebootWipeUserDataAllArgsCalled() {
        verify(() -> RecoverySystem.rebootWipeUserData(mContext, /* shutdown= */ true,
                /* reason= */ REASON, /* force= */ true, /* wipeEuicc= */ true,
                /* keepMemtagMode= */ false));
    }

    private void verifyWipeAdoptableStorageNotCalled() {
        verify(mSm, never()).getDisks();
    }

    private void verifyWipeAdoptableStorageCalled() {
        verify(mStorageInternal).eraseAdoptableDiskForReset(eq(mFirstDisk), anyLong());
    }

    private void verifyWipeFactoryResetProtectionNotCalled() {
        verify(mPdbm, never()).wipe();
    }

    private void verifyWipeFactoryResetProtectionCalled() {
        verify(mPdbm).wipe();
    }
}
