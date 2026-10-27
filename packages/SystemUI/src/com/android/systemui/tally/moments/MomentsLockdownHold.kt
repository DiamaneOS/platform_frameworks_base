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

import android.content.Context
import android.os.Looper
import android.os.UserManager
import com.android.internal.widget.LockPatternUtils
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_TIMEOUT
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject

/**
 * Keeps fingerprint and face unlock off while the switch is in its Lockdown position.
 *
 * Android's Lockdown ends with the next unlock by PIN, pattern or password. While the switch stays
 * there, each time an unlock clears the requirement this asks for the credential again, for the
 * user and their profiles. So the next unlock needs it too, and the lock screen never has a moment
 * where a biometric could unlock. It follows the system's requirement itself, so the order of the
 * unlock's reports does not matter. Once the switch moves back nothing is asked again, and
 * biometrics work after the next unlock with the credential, as after Lockdown.
 *
 * The lock screen asks for the credential as after Lockdown while a requirement it asked for
 * stands ([isAskedFor]).
 *
 * Background thread only, but for [heldFor] and [isAskedFor].
 */
class MomentsLockdownHold(private val auth: Auth) {

    /** The system side. */
    interface Auth {
        /** The user and their enabled profiles. */
        fun profiles(user: Int): List<Int>

        fun isSecure(user: Int): Boolean

        /** Whether a biometric may unlock [user] now: no requirement blocks it. */
        fun biometricAllowed(user: Int): Boolean

        /** Asks for [user]'s credential at the next unlock. */
        fun requireCredential(user: Int)

        /** Calls [onChanged] whenever a user's requirement changes, from now on. */
        fun follow(onChanged: () -> Unit)
    }

    /** Who the switch holds Lockdown for, or null. */
    @Volatile
    var heldFor: Int? = null
        private set

    private var started = false
    // Users whose requirement this asked for and no unlock has cleared yet.
    private val asked = mutableSetOf<Int>()

    /** Sets who the switch holds Lockdown for: [user], or no one (null). */
    fun update(user: Int?) {
        heldFor = user
        if (!started) {
            started = true
            auth.follow { check() }
        }
        check()
    }

    private fun check() {
        asked.removeAll { auth.biometricAllowed(it) }
        val user = heldFor
        val toAsk =
            if (user == null) emptyList()
            else auth.profiles(user).filter { auth.isSecure(it) && auth.biometricAllowed(it) }
        asked += toAsk
        // Before the ask: the lock screen reads it when the requirement changes.
        askedUsers = asked.toSet()
        toAsk.forEach(auth::requireCredential)
    }

    companion object {
        @Volatile private var askedUsers: Set<Int> = emptySet()

        /**
         * Whether [user]'s credential is required because the switch held Lockdown, so the lock
         * screen says Lockdown's reason rather than the security timeout's. Any thread.
         */
        @JvmStatic fun isAskedFor(user: Int): Boolean = user in askedUsers
    }
}

/**
 * [MomentsLockdownHold]'s system side. It asks with the security timeout's reason: unlike
 * Lockdown's, that one does not lock the phone at once or hide notifications while the phone is in
 * use. The lock screen still gives Lockdown's reason for it ([MomentsLockdownHold.isAskedFor]).
 */
@SysUISingleton
class MomentsLockdownAuth
@Inject
constructor(
    @Application private val context: Context,
    @Background private val looper: Looper,
    private val lockPatternUtils: LockPatternUtils,
    private val userManager: UserManager,
) : MomentsLockdownHold.Auth {

    private var tracker: LockPatternUtils.StrongAuthTracker? = null

    override fun profiles(user: Int): List<Int> = userManager.getEnabledProfileIds(user).toList()

    override fun isSecure(user: Int) = lockPatternUtils.isSecure(user)

    override fun biometricAllowed(user: Int) =
        tracker?.isBiometricAllowedForUser(/* isStrongBiometric= */ true, user) == true

    override fun requireCredential(user: Int) =
        lockPatternUtils.requireStrongAuth(STRONG_AUTH_REQUIRED_AFTER_TIMEOUT, user)

    override fun follow(onChanged: () -> Unit) {
        val t =
            object : LockPatternUtils.StrongAuthTracker(context, looper) {
                override fun onStrongAuthRequiredChanged(userId: Int) = onChanged()
            }
        tracker = t
        // The service answers at once with each user's requirement.
        lockPatternUtils.registerStrongAuthTracker(t)
    }
}
