/*
 * Copyright (C) 2026 DiamaneOS
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

package com.android.wm.shell.bubbles

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** DiamaneOS: a bubble is drawn again once its shortcut icon is made, whichever comes first. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class BubbleIconMadeTest {
    @Test
    fun iconMadeBeforeTheBubbleListens_drawsItOnceItListens() {
        val info = BubbleViewInfoTask.BubbleViewInfo()
        var redraws = 0

        info.iconMade.made()
        info.iconMade.listen { redraws++ }

        assertThat(redraws).isEqualTo(1)
    }

    @Test
    fun iconMadeAfterTheBubbleListens_drawsItAtOnce() {
        val info = BubbleViewInfoTask.BubbleViewInfo()
        var redraws = 0

        info.iconMade.listen { redraws++ }
        assertThat(redraws).isEqualTo(0)
        info.iconMade.made()

        assertThat(redraws).isEqualTo(1)
    }

    @Test
    fun aNewListener_doesNotRedrawForTheSameIconAgain() {
        val info = BubbleViewInfoTask.BubbleViewInfo()
        var redraws = 0

        info.iconMade.made()
        info.iconMade.listen { redraws++ }
        info.iconMade.listen { redraws++ }

        assertThat(redraws).isEqualTo(1)
    }
    @Test
    fun aRetiredCallback_doesNotStartAnotherRefill() {
        val made = BubbleIconMade()
        var refills = 0
        made.listen { refills++ }
        made.retire()
        made.made()
        made.listen { refills++ }
        assertThat(refills).isEqualTo(0)
    }

}
