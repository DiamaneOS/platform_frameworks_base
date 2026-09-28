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

package com.android.systemui.tally.privacy

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.tally.TallyShell
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap

/** Tally's privacy indicators that SystemUI starts; none of them is created while Tally is off. */
@Module
interface TallyPrivacyModule {
    companion object {
        @Provides
        @SysUISingleton
        @IntoMap
        @ClassKey(TallyLensIndicator::class)
        fun providesTallyLensIndicator(lensIndicator: Lazy<TallyLensIndicator>): CoreStartable =
            if (TallyShell.isEnabled) lensIndicator.get() else CoreStartable.NOP
    }
}
