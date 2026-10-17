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

package com.android.systemui.tally.icons

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** The keycap icon inflates in the posting app's context and takes its colours from SystemUI. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallyNotificationRowIconViewTest : SysuiTestCase() {

    @Test
    fun inAnotherPackagesContext_drawsTheKeycap() {
        val app = context.createPackageContext("android", 0)
        val view = TallyNotificationRowIconView(app, null, context)
        view.background = ColorDrawable(Color.RED)

        view.setOriginalIconColor(Color.BLUE)

        assertThat(view.background).isInstanceOf(GradientDrawable::class.java)
    }
}
