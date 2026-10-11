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

package com.android.internal.widget;

import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_NONE;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PIN;

import android.annotation.NonNull;

import java.util.Arrays;

/**
 * Sorts lockscreen credentials into strength classes.
 *
 * <p>On a device without a secure element, the credential is all that protects the data of
 * someone whose storage was copied and whose TEE was broken. A credential of the
 * {@link #STRENGTH_WEAKER weaker} class can then be guessed quickly, so LockSettingsService sets
 * one only for a user who accepted that risk.
 *
 * <p>The classes are a floor that can be checked from the type and bytes of a credential alone. A
 * {@link #STRENGTH_STRONG strong} password is not proven to be hard to guess: screens that let a
 * user choose one should rate it more carefully than this class does.
 *
 * @hide
 */
public final class LockCredentialPolicy {

    /**
     * The strength of the credential is not known. Only possible for passwords: their class is
     * not stored, so it is unknown until the password was set or entered since the user was last
     * locked.
     */
    public static final int STRENGTH_UNKNOWN = -1;

    /** There is no credential. */
    public static final int STRENGTH_NONE = 0;

    /**
     * A pattern, a PIN the user chose, or a password below the minimum for
     * {@link #STRENGTH_STRONG}. Needs the user's risk acceptance, unless it replaces a credential
     * that is weaker as well.
     */
    public static final int STRENGTH_WEAKER = 1;

    /**
     * A password that is at least {@link #MIN_STRONG_PASSWORD_LENGTH} characters long, is not made
     * of digits only and has at least {@link #MIN_STRONG_PASSWORD_DISTINCT_CHARS} different
     * characters, or a PIN of at least {@link #MIN_GENERATED_PIN_LENGTH} digits that
     * LockSettingsService generated.
     */
    public static final int STRENGTH_STRONG = 2;

    /** Shortest PIN that is strong when it was generated. Chosen PINs are weaker at any length. */
    public static final int MIN_GENERATED_PIN_LENGTH = 20;

    /** Shortest password that can be strong. */
    public static final int MIN_STRONG_PASSWORD_LENGTH = 20;

    /**
     * Fewest different characters in a strong password. Keeps out a repeated character or pair;
     * it is not an estimate of strength.
     */
    public static final int MIN_STRONG_PASSWORD_DISTINCT_CHARS = 5;

    /**
     * How long a user is given to learn a new strong credential. Only time during which the
     * device is on counts.
     */
    public static final long LEARNING_PERIOD_MILLIS = 14 * 24 * 60 * 60 * 1000L;

    /**
     * During the learning period, biometrics stop working this long after the credential was
     * last entered, so that it is typed about once a day.
     */
    public static final long LEARNING_PERIOD_STRONG_AUTH_TIMEOUT_MILLIS = 24 * 60 * 60 * 1000L;

    private LockCredentialPolicy() {
    }

    /**
     * Returns the strength class of a credential.
     *
     * @param generated whether LockSettingsService generated the credential, with
     *     {@link LockPatternUtils#generateStrongPin}. The service knows what it generated and
     *     does not take a caller's word for it. A screen that checks a PIN before saving it
     *     passes whether it got the PIN from there.
     */
    public static int getStrength(@NonNull LockscreenCredential credential, boolean generated) {
        switch (credential.getType()) {
            case CREDENTIAL_TYPE_NONE:
                return STRENGTH_NONE;
            case CREDENTIAL_TYPE_PIN:
                return generated && credential.size() >= MIN_GENERATED_PIN_LENGTH
                        ? STRENGTH_STRONG : STRENGTH_WEAKER;
            case CREDENTIAL_TYPE_PASSWORD:
                return isStrongPassword(credential.getCredential())
                        ? STRENGTH_STRONG : STRENGTH_WEAKER;
            default:
                // Patterns: there are fewer than 400,000 of them.
                return STRENGTH_WEAKER;
        }
    }

    private static boolean isStrongPassword(byte[] password) {
        if (password.length < MIN_STRONG_PASSWORD_LENGTH) {
            return false;
        }
        boolean digitsOnly = true;
        int distinctChars = 0;
        final boolean[] seen = new boolean[256];
        for (int i = 0; i < password.length; i++) {
            final int c = password[i] & 0xff;
            if (c < '0' || c > '9') {
                digitsOnly = false;
            }
            if (!seen[c]) {
                seen[c] = true;
                distinctChars++;
            }
        }
        // Don't leave the set of characters of the password behind.
        Arrays.fill(seen, false);
        // A password of digits is a PIN, and a PIN is only strong when it was generated.
        return !digitsOnly && distinctChars >= MIN_STRONG_PASSWORD_DISTINCT_CHARS;
    }
}
