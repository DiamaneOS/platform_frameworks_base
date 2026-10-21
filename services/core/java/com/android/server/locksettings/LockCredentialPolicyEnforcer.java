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

import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_NONE;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PIN;
import static com.android.internal.widget.LockPatternUtils.isSpecialUserId;

import android.annotation.Nullable;
import android.app.admin.DevicePolicyManager;
import android.content.pm.UserInfo;
import android.os.Build;
import android.os.Handler;
import android.os.UserManager;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.SparseLongArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.internal.widget.LockCredentialPolicy;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockscreenCredential;

import java.nio.CharBuffer;
import java.security.MessageDigest;
import java.util.function.IntUnaryOperator;

/**
 * Applies {@link LockCredentialPolicy} for {@link LockSettingsService}: which primary credentials
 * may be set, generated PINs, risk acceptances and learning periods. The service keeps the binder
 * entry points and their permission checks, and calls in here.
 */
final class LockCredentialPolicyEnforcer {

    // Lock credential policy state. Only this class writes these keys: the setBoolean(), setLong()
    // and setString() of LockSettingsService refuse them.
    private static final String CREDENTIAL_POLICY_KEY_PREFIX = "de.diamaneos.credential.";
    // Whether the user's PIN is one that generateStrongPin() made. Nothing else about the
    // strength of a credential is stored: see mCredentialStrengths.
    private static final String GENERATED_PIN_KEY =
            CREDENTIAL_POLICY_KEY_PREFIX + "generated_pin";
    // Earlier code stored the strength class and the risk acceptance. Neither is stored any more,
    // since either can say that a password is a weaker one. systemReady() removes what is left.
    private static final String[] LEGACY_CREDENTIAL_POLICY_KEYS = {
            CREDENTIAL_POLICY_KEY_PREFIX + "strength",
            CREDENTIAL_POLICY_KEY_PREFIX + "weaker_risk_accepted",
    };

    // How long a risk acceptance waits for the weaker credential it was given for. Screens record
    // it right before they save, so this only has to cover a slow save.
    @VisibleForTesting
    static final long WEAKER_CREDENTIAL_RISK_ACCEPTANCE_DURATION_MS = 10 * 60 * 1000;

    private static final String LEARNING_PERIOD_REMAINING_KEY =
            CREDENTIAL_POLICY_KEY_PREFIX + "learning_remaining_ms";

    private static final byte[] PERSONALIZATION_GENERATED_PIN = "generated-pin".getBytes();
    private static final int GENERATED_PIN_SALT_LENGTH = 32;

    // While a user learns a new strong credential, biometrics stop working this long after the
    // credential was last entered, so that it is typed about once a day. Afterwards the timeout
    // is what device policy asks for.
    @VisibleForTesting
    static final long LEARNING_PERIOD_STRONG_AUTH_TIMEOUT_MS =
            LockCredentialPolicy.LEARNING_PERIOD_STRONG_AUTH_TIMEOUT_MILLIS;
    // Length of the learning period. Only time during which the device is on counts.
    @VisibleForTesting
    static final long LEARNING_PERIOD_MS = LockCredentialPolicy.LEARNING_PERIOD_MILLIS;

    private final LockSettingsService.Injector mInjector;
    private final LockSettingsStorage mStorage;
    private final LockSettingsStrongAuth mStrongAuth;
    private final UserManager mUserManager;
    private final Handler mHandler;
    // The type of each user's primary credential, as LockSettingsService knows it.
    private final IntUnaryOperator mPrimaryCredentialType;

    // The PIN that generateStrongPin() last made for each user and that has not been set yet.
    // Only a salted hash of it is kept, and only in memory, until a credential is set for the
    // user. Guarded by itself.
    private final SparseArray<GeneratedPin> mGeneratedPins = new SparseArray<>();

    // Time since boot until which each user's acceptance of the risk of a weaker credential holds.
    // It is used up by the next credential that is set for the user. Guarded by itself, and not
    // stored: a stored acceptance would say that a weaker credential is likely in use.
    private final SparseLongArray mWeakerRiskAcceptedUntil = new SparseLongArray();

    // The strength class of each user's primary credential, known from the moment the credential
    // is set or entered until the user is locked or the device restarts. Guarded by itself. It is
    // not stored: next to the stored data it would tell whoever copied the storage whether a
    // password is short.
    private final SparseIntArray mCredentialStrengths = new SparseIntArray();

    // Time since boot up to which each user's learning period has been counted. No entry: nothing
    // was counted since boot. Guarded by itself, as is LEARNING_PERIOD_REMAINING_KEY in storage.
    private final SparseLongArray mLearningPeriodCountedUntil = new SparseLongArray();

    LockCredentialPolicyEnforcer(LockSettingsService.Injector injector,
            LockSettingsStorage storage, LockSettingsStrongAuth strongAuth,
            UserManager userManager, Handler handler, IntUnaryOperator primaryCredentialType) {
        mInjector = injector;
        mStorage = storage;
        mStrongAuth = strongAuth;
        mUserManager = userManager;
        mHandler = handler;
        mPrimaryCredentialType = primaryCredentialType;
    }

    static void checkNotCredentialPolicyKey(String key) {
        if (key.startsWith(CREDENTIAL_POLICY_KEY_PREFIX)) {
            throw new SecurityException("Only LockSettingsService writes " + key);
        }
    }

    /**
     * Returns the {@link LockCredentialPolicy} strength class that the given credential would
     * have as the primary credential of the user.
     */
    int getNewCredentialStrength(LockscreenCredential credential, int userId) {
        return LockCredentialPolicy.getStrength(credential, isGeneratedPin(credential, userId));
    }

    /**
     * Says why a weaker credential was refused for the user and what is accepted instead, for
     * logs and exceptions. Says nothing about the credential that was refused.
     */
    static String describeWeakerCredentialRefusal(int userId) {
        return "User " + userId + " has not accepted the risk of a weaker lockscreen credential"
                + " (pattern, PIN or short password), and the current credential is not known to"
                + " be a weaker one, so none can be set. Accepted without that:"
                + " a password of at least " + LockCredentialPolicy.MIN_STRONG_PASSWORD_LENGTH
                + " characters that is not only digits and has at least "
                + LockCredentialPolicy.MIN_STRONG_PASSWORD_DISTINCT_CHARS
                + " different characters.";
    }

    /**
     * Returns whether a credential of the given strength class may become the primary credential
     * of the user. Having no credential is not judged. A weaker credential may
     * <ul>
     * <li>replace one that is known to be weaker: the user accepted the risk when that one was
     *     set.
     * <li>otherwise be set once after {@link #setWeakerCredentialRiskAccepted}.
     * </ul>
     *
     * @param savedCredential the credential that the caller says is the current one, if it has to
     *     supply it. It need not be verified yet: a wrong one makes the change fail later, so
     *     only the class of the true current credential ever lets a change through.
     */
    boolean isCredentialStrengthAllowed(int strength,
            @Nullable LockscreenCredential savedCredential, int userId) {
        if (!mInjector.isLockCredentialPolicyEnabled()) {
            return true;
        }
        if (strength != LockCredentialPolicy.STRENGTH_WEAKER) {
            return true;
        }
        if (getCredentialStrength(userId) == LockCredentialPolicy.STRENGTH_WEAKER) {
            return true;
        }
        if (savedCredential != null && getCurrentCredentialStrength(savedCredential, userId)
                == LockCredentialPolicy.STRENGTH_WEAKER) {
            return true;
        }
        return isWeakerRiskAccepted(userId);
    }

    boolean isWeakerRiskAccepted(int userId) {
        synchronized (mWeakerRiskAcceptedUntil) {
            return mInjector.getTimeSinceBoot().toMillis()
                    < mWeakerRiskAcceptedUntil.get(userId, 0);
        }
    }

    private void forgetWeakerRiskAcceptance(int userId) {
        synchronized (mWeakerRiskAcceptedUntil) {
            mWeakerRiskAcceptedUntil.delete(userId);
        }
    }

    /** Removes the policy state that earlier code stored, for all users. */
    void removeLegacyCredentialPolicyState() {
        for (UserInfo user : mUserManager.getUsers()) {
            for (String key : LEGACY_CREDENTIAL_POLICY_KEYS) {
                if (mStorage.getString(key, null, user.id) != null) {
                    mStorage.removeKey(key, user.id);
                }
            }
        }
    }

    /**
     * Returns the {@link LockCredentialPolicy} strength class of the user's current primary
     * credential, given that credential.
     */
    private int getCurrentCredentialStrength(LockscreenCredential credential, int userId) {
        return LockCredentialPolicy.getStrength(credential,
                credential.isPin() && mStorage.getBoolean(GENERATED_PIN_KEY, false, userId));
    }

    /**
     * Remembers the strength class of the user's primary credential for
     * {@link #getCredentialStrength}, in memory only.
     */
    private void rememberCredentialStrength(int strength, int userId) {
        synchronized (mCredentialStrengths) {
            mCredentialStrengths.put(userId, strength);
        }
    }

    private void forgetCredentialStrength(int userId) {
        synchronized (mCredentialStrengths) {
            mCredentialStrengths.delete(userId);
        }
    }

    /**
     * Called when the user's primary credential was verified. This and setting a credential are
     * the only times this service sees the credential and can tell its strength class.
     */
    void onPrimaryCredentialVerified(LockscreenCredential credential, int userId) {
        if (!mInjector.isLockCredentialPolicyEnabled()) {
            return;
        }
        rememberCredentialStrength(getCurrentCredentialStrength(credential, userId), userId);
    }

    /**
     * Called when the given credential, of the given strength class, was set as the primary
     * credential of the user. Updates what {@link #getCredentialStrength} answers from, uses up
     * the user's risk acceptance, and starts or ends the user's learning period.
     */
    void onPrimaryCredentialChanged(LockscreenCredential credential, int strength,
            boolean isLockTiedToParent, int userId) {
        forgetGeneratedPin(userId);
        // An acceptance is for one weaker credential, and for none if another kind was set.
        forgetWeakerRiskAcceptance(userId);
        if (!mInjector.isLockCredentialPolicyEnabled()) {
            return;
        }
        rememberCredentialStrength(strength, userId);
        // A generated PIN cannot be told from a chosen one later, so this much is stored. A PIN
        // is only strong if it was generated.
        if (credential.isPin() && strength == LockCredentialPolicy.STRENGTH_STRONG) {
            mStorage.setBoolean(GENERATED_PIN_KEY, true, userId);
        } else if (mStorage.getString(GENERATED_PIN_KEY, null, userId) != null) {
            mStorage.removeKey(GENERATED_PIN_KEY, userId);
        }
        // A strong credential is long, and the user has to learn it: only for a strong one is
        // the strong auth timeout limited. The learning time is stored and counted for every
        // password all the same, so that the stored state does not tell a strong password from
        // a weaker one. For a PIN the stored type and mark already tell. The random password of
        // a profile whose lock is tied to its parent's is never typed.
        final boolean strong = strength == LockCredentialPolicy.STRENGTH_STRONG;
        setLearningPeriod(/* counted= */ !isLockTiedToParent && (credential.isPassword() || strong),
                /* limited= */ !isLockTiedToParent && strong, userId);
    }

    /** Called with the caller's identity cleared. */
    void setWeakerCredentialRiskAccepted(boolean accepted, int userId) {
        checkCredentialPolicyUser(userId);
        synchronized (mWeakerRiskAcceptedUntil) {
            if (accepted) {
                mWeakerRiskAcceptedUntil.put(userId, mInjector.getTimeSinceBoot().toMillis()
                        + WEAKER_CREDENTIAL_RISK_ACCEPTANCE_DURATION_MS);
            } else {
                mWeakerRiskAcceptedUntil.delete(userId);
            }
        }
    }

    /**
     * Refuses user IDs that are not in use: state kept for one would be inherited by the next
     * user that gets the ID.
     */
    private void checkCredentialPolicyUser(int userId) {
        if (isSpecialUserId(userId) || mUserManager.getUserInfo(userId) == null) {
            throw new IllegalArgumentException("No such user: " + userId);
        }
    }

    private static final class GeneratedPin {
        final byte[] mSalt;
        final byte[] mHash;

        GeneratedPin(byte[] salt, byte[] hash) {
            mSalt = salt;
            mHash = hash;
        }

        void zeroize() {
            ArrayUtils.zeroize(mSalt);
            ArrayUtils.zeroize(mHash);
        }
    }

    /**
     * Makes a random PIN for the user. A PIN is of the strong class only if it came from here:
     * a caller cannot tell this service that a PIN was generated, it can only set the PIN that
     * this service generated. See {@link LockCredentialPolicy}. Called with the caller's identity
     * cleared.
     */
    LockscreenCredential generateStrongPin(int length, int userId) {
        Preconditions.checkArgumentInRange(length, LockCredentialPolicy.MIN_GENERATED_PIN_LENGTH,
                DevicePolicyManager.MAX_PASSWORD_LENGTH, "length");
        checkCredentialPolicyUser(userId);
        final LockscreenCredential pin = createRandomPin(length);
        final byte[] salt = SecureRandomUtils.randomBytes(GENERATED_PIN_SALT_LENGTH);
        final GeneratedPin generatedPin = new GeneratedPin(salt, hashGeneratedPin(salt, pin));
        synchronized (mGeneratedPins) {
            forgetGeneratedPin(userId);
            mGeneratedPins.put(userId, generatedPin);
        }
        return pin;
    }

    private static LockscreenCredential createRandomPin(int length) {
        final char[] digits = new char[length];
        try {
            int count = 0;
            while (count < length) {
                final byte[] random = SecureRandomUtils.randomBytes(length);
                for (int i = 0; i < random.length && count < length; i++) {
                    final int value = random[i] & 0xff;
                    // Skip 250 to 255, so that each digit is as likely as any other.
                    if (value < 250) {
                        digits[count++] = (char) ('0' + value % 10);
                    }
                }
                ArrayUtils.zeroize(random);
            }
            return LockscreenCredential.createPin(CharBuffer.wrap(digits));
        } finally {
            ArrayUtils.zeroize(digits);
        }
    }

    private static byte[] hashGeneratedPin(byte[] salt, LockscreenCredential pin) {
        return SyntheticPasswordCrypto.personalizedHash(PERSONALIZATION_GENERATED_PIN, salt,
                pin.getCredential());
    }

    /** Returns whether the credential is the PIN that this service last generated for the user. */
    private boolean isGeneratedPin(LockscreenCredential credential, int userId) {
        if (!credential.isPin()) {
            return false;
        }
        synchronized (mGeneratedPins) {
            final GeneratedPin generatedPin = mGeneratedPins.get(userId);
            if (generatedPin == null) {
                return false;
            }
            final byte[] hash = hashGeneratedPin(generatedPin.mSalt, credential);
            try {
                return MessageDigest.isEqual(hash, generatedPin.mHash);
            } finally {
                ArrayUtils.zeroize(hash);
            }
        }
    }

    /**
     * Starts the learning period of the primary credential that was just set for the user, or
     * ends the one that is running.
     *
     * <p>The period is kept as the time that remains of it, and counted down on the time since
     * boot by {@link #updateLearningPeriod}. The wall clock plays no part: it may be wrong when
     * the credential is set, and it can be changed.
     *
     * @param counted whether to store and count the learning time
     * @param limited whether to limit the strong auth timeout meanwhile. Implies {@code counted}
     */
    private void setLearningPeriod(boolean counted, boolean limited, int userId) {
        final boolean wasCounted;
        synchronized (mLearningPeriodCountedUntil) {
            wasCounted = mStorage.getLong(LEARNING_PERIOD_REMAINING_KEY, 0, userId) > 0;
            if (counted) {
                mStorage.setLong(LEARNING_PERIOD_REMAINING_KEY, LEARNING_PERIOD_MS, userId);
                mLearningPeriodCountedUntil.put(userId, mInjector.getTimeSinceBoot().toMillis());
            } else if (wasCounted) {
                mStorage.removeKey(LEARNING_PERIOD_REMAINING_KEY, userId);
                mLearningPeriodCountedUntil.delete(userId);
            }
        }
        if (!limited && !wasCounted) {
            // No limit was in force and none is wanted.
            return;
        }
        mStrongAuth.setStrongAuthTimeoutLimit(
                limited ? LEARNING_PERIOD_STRONG_AUTH_TIMEOUT_MS : 0, userId);
        // Also for a timeout that is pending. That one counts from when the user's credential was
        // last entered, which a change made in Settings did a moment ago.
        mStrongAuth.refreshStrongAuthTimeout(userId);
    }

    /**
     * Counts the time that passed off the user's learning period, and tells
     * {@link LockSettingsStrongAuth} to limit the strong auth timeout while the period runs and
     * the user's credential is known to be a strong one.
     *
     * <p>Called whenever the user unlocks, with the credential or without. The stored time is
     * then as fresh for a weaker password, which is entered less often, as for a strong one.
     * When called for an entry of the credential, this comes before the strong auth timeout is
     * scheduled.
     *
     * <p>After a restart the time since boot is counted. Time during which the device was off is
     * not, so the period can run longer than {@link #LEARNING_PERIOD_MS} but not shorter.
     *
     * <p>The class of a password is only known once it was entered. Until then no limit is set:
     * after a restart that is until the first unlock with the password, which strong auth after
     * boot asks for anyway.
     */
    void updateLearningPeriod(int userId) {
        final long remaining = countLearningPeriod(userId);
        if (remaining < 0) {
            return;
        }
        if (remaining == 0) {
            // A timeout that is pending keeps its time, so the credential may be asked for early
            // once more.
            mStrongAuth.setStrongAuthTimeoutLimit(0, userId);
        } else if (getCredentialStrength(userId)
                == LockCredentialPolicy.STRENGTH_STRONG) {
            mStrongAuth.setStrongAuthTimeoutLimit(LEARNING_PERIOD_STRONG_AUTH_TIMEOUT_MS, userId);
        }
        // Otherwise the credential is a weaker password, for which no limit was set, or it is
        // not known what it is, and a limit that was set before stays.
    }

    /**
     * Counts the time since the last call off the user's stored learning time, in the same way
     * whatever the class of the credential is.
     *
     * @return the time that is left, 0 if the period ended just now, or -1 if none was running
     */
    private long countLearningPeriod(int userId) {
        synchronized (mLearningPeriodCountedUntil) {
            long remaining = mStorage.getLong(LEARNING_PERIOD_REMAINING_KEY, 0, userId);
            if (remaining <= 0) {
                return -1;
            }
            final long now = mInjector.getTimeSinceBoot().toMillis();
            remaining = Math.min(remaining, LEARNING_PERIOD_MS)
                    - Math.max(0, now - mLearningPeriodCountedUntil.get(userId, 0));
            if (remaining > 0) {
                mStorage.setLong(LEARNING_PERIOD_REMAINING_KEY, remaining, userId);
                mLearningPeriodCountedUntil.put(userId, now);
                return remaining;
            }
            mStorage.removeKey(LEARNING_PERIOD_REMAINING_KEY, userId);
            mLearningPeriodCountedUntil.delete(userId);
            return 0;
        }
    }

    long getLearningPeriodRemainingMillis(int userId) {
        // The learning time is counted for every password, but only a strong one is asked for
        // more often. This answers from the class that getCredentialStrength() returns to the
        // same callers, so it tells them nothing more about the credential.
        if (getCredentialStrength(userId) != LockCredentialPolicy.STRENGTH_STRONG) {
            return 0;
        }
        synchronized (mLearningPeriodCountedUntil) {
            final long remaining = mStorage.getLong(LEARNING_PERIOD_REMAINING_KEY, 0, userId);
            final long notCounted = Math.max(0, mInjector.getTimeSinceBoot().toMillis()
                    - mLearningPeriodCountedUntil.get(userId, 0));
            return Math.max(0, Math.min(remaining, LEARNING_PERIOD_MS) - notCounted);
        }
    }

    /**
     * Sets the learning time that is left for the user. Only for
     * {@link LockSettingsShellCommand}, which offers it on debuggable builds, so that what
     * depends on the time can be tested without waiting for days.
     *
     * <p>Only a learning period that is running can be changed. A credential that has none,
     * such as a chosen PIN, a pattern, or one whose time is up, gets none this way.
     *
     * @param remainingMs from 0, which ends the period, to {@link #LEARNING_PERIOD_MS}
     * @return false if the user has no learning period, in which case nothing is changed
     */
    boolean setLearningPeriodRemainingForShell(long remainingMs, int userId) {
        if (remainingMs < 0 || remainingMs > LEARNING_PERIOD_MS) {
            throw new IllegalArgumentException("Learning time out of range: " + remainingMs);
        }
        // Count first: a period whose time is up is over, also when no unlock has noted it yet.
        updateLearningPeriod(userId);
        synchronized (mLearningPeriodCountedUntil) {
            if (mStorage.getLong(LEARNING_PERIOD_REMAINING_KEY, 0, userId) <= 0) {
                return false;
            }
            if (remainingMs > 0) {
                mStorage.setLong(LEARNING_PERIOD_REMAINING_KEY, remainingMs, userId);
                mLearningPeriodCountedUntil.put(userId, mInjector.getTimeSinceBoot().toMillis());
            } else {
                mStorage.removeKey(LEARNING_PERIOD_REMAINING_KEY, userId);
                mLearningPeriodCountedUntil.delete(userId);
            }
        }
        if (remainingMs == 0) {
            // As when the time is up: see updateLearningPeriod().
            mStrongAuth.setStrongAuthTimeoutLimit(0, userId);
        }
        return true;
    }

    private void forgetGeneratedPin(int userId) {
        synchronized (mGeneratedPins) {
            final GeneratedPin generatedPin = mGeneratedPins.get(userId);
            if (generatedPin != null) {
                generatedPin.zeroize();
                mGeneratedPins.remove(userId);
            }
        }
    }

    int getCredentialStrength(int userId) {
        switch (mPrimaryCredentialType.applyAsInt(userId)) {
            case CREDENTIAL_TYPE_NONE:
                return LockCredentialPolicy.STRENGTH_NONE;
            case LockPatternUtils.CREDENTIAL_TYPE_PATTERN:
                return LockCredentialPolicy.STRENGTH_WEAKER;
            case CREDENTIAL_TYPE_PIN:
                // Strong only if it is the PIN that this service generated.
                return mStorage.getBoolean(GENERATED_PIN_KEY, false, userId)
                        ? LockCredentialPolicy.STRENGTH_STRONG
                        : LockCredentialPolicy.STRENGTH_WEAKER;
            default:
                // A password. Its class is known only once it was set or entered since the user
                // was last locked: not after a restart, and not when the user was unlocked
                // without it, for example with an escrow token.
                synchronized (mCredentialStrengths) {
                    return mCredentialStrengths.get(userId,
                            LockCredentialPolicy.STRENGTH_UNKNOWN);
                }
        }
    }

    /** Forgets what is kept in memory for a user who was removed. */
    void removeUser(int userId) {
        forgetGeneratedPin(userId);
        forgetCredentialStrength(userId);
        forgetWeakerRiskAcceptance(userId);
        synchronized (mLearningPeriodCountedUntil) {
            mLearningPeriodCountedUntil.delete(userId);
        }
    }

    /** Forgets what is known only while the user is unlocked. */
    void onUserLocked(int userId) {
        forgetCredentialStrength(userId);
        forgetWeakerRiskAcceptance(userId);
    }

    void onUserPresent(int userId) {
        // Every unlock counts the learning time, not only an entry of the credential: see
        // updateLearningPeriod(). Off this thread, since it writes to storage.
        if (mStorage.getLong(LEARNING_PERIOD_REMAINING_KEY, 0, userId) > 0) {
            mHandler.post(() -> updateLearningPeriod(userId));
        }
    }

    void dump(IndentingPrintWriter pw, int userId) {
        // Stored policy state only. The strength class of a password, a risk acceptance and
        // whether a PIN was generated are not printed. The learning time is stored for every
        // password but only for a generated PIN, so user builds leave it out too.
        if (Build.IS_DEBUGGABLE) {
            pw.println("LearningPeriodRemainingMs: "
                    + mStorage.getLong(LEARNING_PERIOD_REMAINING_KEY, 0, userId));
        }
    }
}
