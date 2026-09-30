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

package com.android.systemui.tally.shade

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.NotificationSectionsFeatureManager
import com.android.systemui.statusbar.notification.OnboardingAffordanceManager
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.coordinator.ColorizedFgsCoordinator
import com.android.systemui.statusbar.notification.collection.coordinator.ConversationCoordinator
import com.android.systemui.statusbar.notification.collection.coordinator.RankingCoordinator
import com.android.systemui.statusbar.notification.collection.listbuilder.NotifSection
import com.android.systemui.statusbar.notification.collection.provider.SectionHeaderVisibilityProvider
import com.android.systemui.statusbar.notification.collection.render.BundleBarn
import com.android.systemui.statusbar.notification.collection.render.MediaContainerController
import com.android.systemui.statusbar.notification.collection.render.NodeController
import com.android.systemui.statusbar.notification.collection.render.NodeSpec
import com.android.systemui.statusbar.notification.collection.render.NodeSpecBuilder
import com.android.systemui.statusbar.notification.collection.render.NodeSpecBuilderLogger
import com.android.systemui.statusbar.notification.collection.render.NotifViewBarn
import com.android.systemui.statusbar.notification.collection.render.SectionHeaderController
import com.android.systemui.tally.TallyShell
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The shade's notification sections and their headers, with the real sectioners in stock's order
 * (NotifCoordinators) and the real NodeSpecBuilder. With Tally on, every section present has its
 * header (Live, Conversations, Notifications, Silent); stock shows Silent's only. Either way a
 * header shows only for a section with notifications, and none shows on the lock screen.
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
class TallySectionHeadersTest : SysuiTestCase() {

    private val liveHeader = header("live header")
    private val peopleHeader = header("people header")
    private val alertingHeader = header("alerting header")
    private val silentHeader = header("silent header")

    private val live = ColorizedFgsCoordinator(TestScope(), mock(), liveHeader).sectioner
    private val conversations = ConversationCoordinator(mock(), mock(), mock(), peopleHeader)
    private val ranking =
        RankingCoordinator(
            mock(),
            mock(),
            alertingHeader,
            mock<SectionHeaderController>(),
            silentHeader,
        )

    private val sections =
        listOf(
                live,
                conversations.priorityPeopleSectioner,
                conversations.peopleAlertingSectioner,
                ranking.alertingSectioner,
                ranking.silentSectioner,
                ranking.minimizedSectioner,
            )
            .mapIndexed { i, sectioner -> NotifSection(sectioner, i) }
    private val liveSection = sections[0]
    private val prioritySection = sections[1]
    private val peopleSection = sections[2]
    private val alertingSection = sections[3]
    private val silentSection = sections[4]
    private val minimizedSection = sections[5]

    private val headersVisible = mock<SectionHeaderVisibilityProvider>()
    private var entries = 0
    private val viewBarn = mock<NotifViewBarn>()
    private val specBuilder =
        NodeSpecBuilder(
            mock<MediaContainerController>(),
            mock<NotificationSectionsFeatureManager>(),
            headersVisible,
            viewBarn,
            mock<BundleBarn>(),
            mock<NodeSpecBuilderLogger>(),
            mock<OnboardingAffordanceManager>(),
            mock<OnboardingAffordanceManager>(),
        )

    @Test
    fun everySectionHasItsHeader_inTally() {
        val tally = TallyShell.isEnabled
        assertThat(liveSection.headerController).isEqualTo(if (tally) liveHeader else null)
        assertThat(prioritySection.headerController).isEqualTo(if (tally) peopleHeader else null)
        assertThat(peopleSection.headerController).isEqualTo(if (tally) peopleHeader else null)
        assertThat(alertingSection.headerController).isEqualTo(if (tally) alertingHeader else null)
        assertThat(silentSection.headerController).isEqualTo(silentHeader)
        assertThat(minimizedSection.headerController).isEqualTo(silentHeader)
    }

    @Test
    fun liveNotificationsAndSilent_eachUnderItsHeader() {
        assertThat(
                build(
                    entry(liveSection),
                    entry(alertingSection),
                    entry(alertingSection),
                    entry(silentSection),
                )
            )
            .containsExactlyElementsIn(
                if (TallyShell.isEnabled) {
                    listOf(
                        "live header",
                        "entry 0",
                        "alerting header",
                        "entry 1",
                        "entry 2",
                        "silent header",
                        "entry 3",
                    )
                } else {
                    listOf("entry 0", "entry 1", "entry 2", "silent header", "entry 3")
                }
            )
            .inOrder()
    }

    @Test
    fun aSectionWithoutNotifications_hasNoHeader() {
        assertThat(build(entry(alertingSection)))
            .containsExactlyElementsIn(
                if (TallyShell.isEnabled) listOf("alerting header", "entry 0")
                else listOf("entry 0")
            )
            .inOrder()
    }

    @Test
    fun priorityAndOtherConversations_shareOneHeader() {
        assertThat(build(entry(prioritySection), entry(peopleSection), entry(silentSection)))
            .containsExactlyElementsIn(
                if (TallyShell.isEnabled) {
                    listOf("people header", "entry 0", "entry 1", "silent header", "entry 2")
                } else {
                    listOf("entry 0", "entry 1", "silent header", "entry 2")
                }
            )
            .inOrder()
    }

    @Test
    fun silentAndMinimized_shareSilentsHeader() {
        assertThat(build(entry(silentSection), entry(minimizedSection)))
            .containsExactly("silent header", "entry 0", "entry 1")
            .inOrder()
    }

    @Test
    fun onTheLockScreen_noHeaders() {
        // KeyguardCoordinator hides every header on the keyguard, stock and Tally alike.
        whenever(headersVisible.sectionHeadersVisible).thenReturn(false)
        assertThat(
                specBuilder
                    .buildNodeSpec(
                        header("root"),
                        listOf(entry(liveSection), entry(alertingSection), entry(silentSection)),
                    )
                    .children
                    .map { it.controller.nodeLabel }
            )
            .containsExactly("entry 0", "entry 1", "entry 2")
            .inOrder()
    }

    /** The labels of the shade's nodes for [entries], headers shown as in the unlocked shade. */
    private fun build(vararg entries: NotificationEntry): List<String> {
        whenever(headersVisible.sectionHeadersVisible).thenReturn(true)
        val spec: NodeSpec = specBuilder.buildNodeSpec(header("root"), entries.toList())
        return spec.children.map { it.controller.nodeLabel }
    }

    private fun entry(section: NotifSection): NotificationEntry {
        val entry = mock<NotificationEntry> { on { this.section } doReturn section }
        val view = header("entry ${entries++}")
        whenever(viewBarn.requireNodeController(entry)).thenReturn(view)
        return entry
    }

    private fun header(label: String): NodeController = mock { on { nodeLabel } doReturn label }
}
