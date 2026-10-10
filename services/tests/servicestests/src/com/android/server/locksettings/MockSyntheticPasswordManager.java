/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static org.junit.Assert.assertNull;

import android.content.Context;
import android.hardware.weaver.IWeaver;
import android.os.RemoteException;
import android.os.UserManager;

import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.time.Duration;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class MockSyntheticPasswordManager extends SyntheticPasswordManager {

    private MockWeaverService mWeaverService;
    private IWeaver mWeaverAidl;
    private android.hardware.weaver.V1_0.IWeaver mWeaverHidl;

    /** The number of scrypt() calls made so far, including failed ones. */
    int mScryptCallCount;
    /** The parameters of the most recent scrypt() call. */
    int mLastScryptN;
    int mLastScryptR;
    int mLastScryptP;
    // The number of scrypt() calls that still succeed before scrypt() starts failing, or -1 if it
    // never fails.
    private int mScryptSuccessesLeft = -1;

    public MockSyntheticPasswordManager(
            Context context,
            LockSettingsStorage storage,
            UserManager userManager,
            PasswordSlotManager passwordSlotManager,
            KeyStore keyStore) {
        super(context, storage, userManager, passwordSlotManager, keyStore, false);
    }

    @Override
    protected long sidFromPasswordHandle(byte[] handle) {
        return new FakeGateKeeperService.VerifyHandle(handle).sid;
    }

    @Override
    protected byte[] scrypt(byte[] password, byte[] salt, int n, int r, int p, int outLen) {
        mScryptCallCount++;
        mLastScryptN = n;
        mLastScryptR = r;
        mLastScryptP = p;
        if (mScryptSuccessesLeft == 0) {
            return null;
        }
        if (mScryptSuccessesLeft > 0) {
            mScryptSuccessesLeft--;
        }
        try {
            char[] passwordChars = new char[password.length];
            for (int i = 0; i < password.length; i++) {
                passwordChars[i] = (char) password[i];
            }
            PBEKeySpec spec = new PBEKeySpec(passwordChars, salt, 10, outLen * 8);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            return f.generateSecret(spec).getEncoded();
        } catch (InvalidKeySpecException | NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Makes scrypt() return null, like the real one does when it can't allocate its memory, once
     * {@code successfulCalls} more calls have succeeded.
     */
    public void failScryptAfter(int successfulCalls) {
        mScryptSuccessesLeft = successfulCalls;
    }

    /** Makes scrypt() succeed again. */
    public void clearScryptFailure() {
        mScryptSuccessesLeft = -1;
    }

    /** Enables MockWeaverService. */
    public void enableWeaver() {
        enableWeaverAidl();
    }

    /** Enables MockWeaverService with the current (AIDL) interface. */
    public void enableWeaverAidl() {
        assertNull(mWeaverService);
        mWeaverService = new MockWeaverService();
        mWeaverAidl = mWeaverService;
    }

    @Override
    protected IWeaver getWeaverAidlService() {
        return mWeaverAidl;
    }

    /** Enables MockWeaverService with the old (HIDL) interface. */
    public void enableWeaverHidl() {
        assertNull(mWeaverService);
        mWeaverService = new MockWeaverService();
        mWeaverHidl = mWeaverService.asHidl();
    }

    @Override
    protected android.hardware.weaver.V1_0.IWeaver getWeaverHidlService() throws RemoteException {
        return mWeaverHidl;
    }

    public boolean isWeaverEnabled() {
        return mWeaverService != null;
    }

    public int getSumOfWeaverFailureCounters() {
        return mWeaverService.getSumOfFailureCounters();
    }

    /** Injects a response to be returned by the next read from Weaver. */
    public void injectWeaverReadResponse(int status, Duration timeout) {
        mWeaverService.injectReadResponse(status, timeout);
    }

    /** Injects a timeout to be returned by the next getTimeout call to Weaver. */
    public void injectWeaverTimeout(Duration timeout) {
        mWeaverService.injectTimeout(timeout);
    }
}
