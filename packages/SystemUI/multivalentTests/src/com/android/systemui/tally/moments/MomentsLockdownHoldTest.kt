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

package com.android.systemui.tally.moments

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The switch's Lockdown position keeps biometric unlock off: each unlock with the credential is
 * followed by a new requirement while the switch holds Lockdown, and by none once it moves back.
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
class MomentsLockdownHoldTest : SysuiTestCase() {

    /** Users whose requirement is cleared (an unlock) or set, as LockSettingsService keeps it. */
    private class FakeAuth : MomentsLockdownHold.Auth {
        val profiles = mutableMapOf(USER to listOf(USER, PROFILE), OTHER to listOf(OTHER))
        val secure = mutableSetOf(USER, PROFILE, OTHER)
        val required = mutableSetOf(USER, PROFILE, OTHER) // locked after boot
        val asked = mutableListOf<Int>()
        var onChanged: (() -> Unit)? = null

        override fun profiles(user: Int) = profiles[user] ?: listOf(user)

        override fun isSecure(user: Int) = user in secure

        override fun biometricAllowed(user: Int) = user !in required

        override fun requireCredential(user: Int) {
            asked += user
            required += user
        }

        override fun follow(onChanged: () -> Unit) {
            this.onChanged = onChanged
        }

        /** An unlock with the credential: the service clears the requirement and reports it. */
        fun unlock(vararg users: Int) {
            required -= users.toSet()
            onChanged?.invoke()
        }
    }

    private val auth = FakeAuth()
    private val hold = MomentsLockdownHold(auth)

    @After
    fun tearDown() {
        // The reason is kept for the whole process: leave none for the next test.
        hold.update(null)
        auth.unlock(USER, PROFILE, OTHER)
    }

    @Test
    fun held_eachUnlockIsFollowedByANewRequirement() {
        hold.update(USER)
        assertThat(auth.asked).isEmpty() // Lockdown itself still holds

        auth.unlock(USER, PROFILE)
        assertThat(auth.asked).containsExactly(USER, PROFILE)
        assertThat(auth.biometricAllowed(USER)).isFalse()

        auth.unlock(USER, PROFILE)
        assertThat(auth.asked).containsExactly(USER, PROFILE, USER, PROFILE)
    }

    @Test
    fun movedBack_theNextUnlockAllowsBiometricsAgain() {
        hold.update(USER)
        auth.unlock(USER, PROFILE)
        hold.update(null)
        // Still required once: the switch moving back does not lift it.
        assertThat(auth.biometricAllowed(USER)).isFalse()

        auth.unlock(USER, PROFILE)
        assertThat(auth.biometricAllowed(USER)).isTrue()
        assertThat(auth.asked).containsExactly(USER, PROFILE)
    }

    @Test
    fun heldWhileNothingIsRequired_asksAtOnce() {
        // As after a SystemUI restart while the phone is in use, once the service has reported.
        auth.required.clear()
        hold.update(USER)
        assertThat(auth.asked).containsExactly(USER, PROFILE)
    }

    @Test
    fun lockScreenReason_followsTheRequirementItAskedFor() {
        hold.update(USER)
        // Lockdown's own requirement, not one the hold asked for.
        assertThat(MomentsLockdownHold.isAskedFor(USER)).isFalse()

        auth.unlock(USER, PROFILE)
        assertThat(MomentsLockdownHold.isAskedFor(USER)).isTrue()
        assertThat(MomentsLockdownHold.isAskedFor(PROFILE)).isTrue()

        // Moved back: the reason stays until the unlock clears the requirement.
        hold.update(null)
        assertThat(MomentsLockdownHold.isAskedFor(USER)).isTrue()
        auth.unlock(USER, PROFILE)
        assertThat(MomentsLockdownHold.isAskedFor(USER)).isFalse()
        assertThat(MomentsLockdownHold.isAskedFor(PROFILE)).isFalse()
    }

    @Test
    fun onlyTheHeldUserAndTheirProfiles() {
        hold.update(USER)
        auth.unlock(OTHER)
        assertThat(auth.asked).isEmpty()

        // After a switch to a user whose switch does not hold Lockdown.
        hold.update(null)
        auth.unlock(USER, PROFILE)
        assertThat(auth.asked).isEmpty()
    }

    @Test
    fun noSecureLock_asksNothing() {
        auth.secure.clear()
        auth.required.clear()
        hold.update(USER)
        assertThat(auth.asked).isEmpty()
    }

    @Test
    fun followsOnce() {
        hold.update(USER)
        val first = auth.onChanged
        hold.update(null)
        hold.update(USER)
        assertThat(auth.onChanged).isSameInstanceAs(first)
    }

    private companion object {
        const val USER = 0
        const val PROFILE = 10
        const val OTHER = 11
    }
}
