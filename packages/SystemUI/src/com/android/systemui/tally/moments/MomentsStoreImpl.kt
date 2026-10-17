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
import android.content.SharedPreferences
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject

/**
 * Keeps the switch's record in SystemUI's own device-protected storage: private to SystemUI,
 * readable before the first unlock, and kept across restarts and reboots.
 */
@SysUISingleton
class MomentsStoreImpl @Inject constructor(@Application private val context: Context) :
    MomentsStore {

    private val prefs: SharedPreferences by lazy {
        context
            .createDeviceProtectedStorageContext()
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    override fun record(user: Int): List<MomentsApplied> =
        MomentsRecordCodec.decode(prefs.getString("record_$user", null))

    override fun setRecord(user: Int, record: List<MomentsApplied>) {
        // Committed at once: the record must be on disk before the next change is made.
        prefs.edit().putString("record_$user", MomentsRecordCodec.encode(record)).commit()
    }

    override fun hasFlipped(user: Int): Boolean = prefs.getBoolean("flipped_$user", false)

    override fun setFlipped(user: Int) {
        prefs.edit().putBoolean("flipped_$user", true).commit()
    }

    override fun noticeShown(user: Int): Boolean = prefs.getBoolean("notice_$user", false)

    override fun setNoticeShown(user: Int) {
        prefs.edit().putBoolean("notice_$user", true).commit()
    }

    private companion object {
        const val FILE = "tally_moments"
    }
}
