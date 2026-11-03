/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.wm.shell.hierarchy.updates

import android.app.ActivityManager
import android.hardware.devicestate.DeviceStateManager
import android.os.Handler
import android.view.Display.DEFAULT_DISPLAY
import android.view.SurfaceControl
import android.window.DisplayAreaOrganizer.FEATURE_DEFAULT_TASK_CONTAINER
import android.window.TaskAppearedInfo
import android.window.WindowContainerToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestSyncExecutor
import com.android.wm.shell.common.DisplayInsetsController
import com.android.wm.shell.hierarchy.ContainerHierarchy
import com.android.wm.shell.hierarchy.containers.StubContainer
import com.android.wm.shell.hierarchy.modes.FormFactorModes
import com.android.wm.shell.hierarchy.properties.DisplayAreaContainerProperties
import com.android.wm.shell.hierarchy.properties.DisplayContainerProperties
import com.android.wm.shell.hierarchy.properties.TaskContainerProperties
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.Transitions
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

/** Ownership itself does not depend on Shell Modes being enabled or overridable. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class HierarchyRootTaskOwnershipTest : ShellTestCase() {
    private val hierarchy = ContainerHierarchy()
    private val display = StubContainer(
        WindowContainerToken.createProxy("Display"), DisplayContainerProperties(DEFAULT_DISPLAY)
    ).apply { parent = hierarchy.root }
    private val displayArea = StubContainer(
        WindowContainerToken.createProxy("DisplayArea"),
        DisplayAreaContainerProperties(FEATURE_DEFAULT_TASK_CONTAINER)
    ).apply { parent = display }
    private lateinit var updater: HierarchyUpdater

    @Before
    fun setup() {
        updater = HierarchyUpdater(
            context, mock<ShellController>(), mock<ShellTaskOrganizer>(), mock<Transitions>(),
            mock<DisplayInsetsController>(), mock<DeviceStateManager>(), hierarchy,
            mock<FormFactorModes>(), mock<ShellInit>(), TestSyncExecutor(), mock<Handler>()
        )
    }

    private fun taskInfo() = ActivityManager.RunningTaskInfo().apply {
        token = WindowContainerToken.createProxy("Task")
        displayId = DEFAULT_DISPLAY
    }

    private fun surface() = SurfaceControl.Builder()
        .setName("HierarchyRootTaskOwnershipTest").setContainerLayer().build()

    @Test
    fun newContainerAdoptsWrapper() {
        val info = taskInfo()
        val leash = surface()
        try {
            assertThat(updater.handleCreateRootTask(TaskAppearedInfo(info, leash), "new")).isTrue()
            assertThat(hierarchy.getContainer(info.token)!!.ownsLeash(leash)).isTrue()
            assertThat(leash.isValid).isTrue()
        } finally {
            leash.release()
        }
    }

    @Test
    fun existingContainerKeepsItsExactWrapper() {
        val info = taskInfo()
        val leash = surface()
        StubContainer(info.token, TaskContainerProperties(info)).apply {
            parent = displayArea
            this.leash = leash
        }
        try {
            assertThat(updater.handleCreateRootTask(TaskAppearedInfo(info, leash), "same")).isTrue()
            assertThat(leash.isValid).isTrue()
        } finally {
            leash.release()
        }
    }

    @Test
    fun existingContainerDeclinesIndependentWrapperOfSameSurface() {
        val info = taskInfo()
        val owned = surface()
        val incoming = SurfaceControl(owned, "HierarchyRootTaskOwnershipTest")
        StubContainer(info.token, TaskContainerProperties(info)).apply {
            parent = displayArea
            leash = owned
        }
        try {
            assertThat(updater.handleCreateRootTask(TaskAppearedInfo(info, incoming), "copy"))
                .isFalse()
            incoming.release() // Organizer disposal must preserve the hierarchy's independent ref.
            assertThat(owned.isValid).isTrue()
        } finally {
            incoming.release()
            owned.release()
        }
    }

    @Test
    fun existingContainerWithoutLeashDeclinesWithoutThrowing() {
        val info = taskInfo()
        val incoming = surface()
        val existing = StubContainer(info.token, TaskContainerProperties(info)).apply {
            parent = displayArea
        }
        try {
            assertThat(updater.handleCreateRootTask(TaskAppearedInfo(info, incoming), "pending"))
                .isFalse()
            assertThat(existing.ownsLeash(incoming)).isFalse()
            assertThat(incoming.isValid).isTrue()
        } finally {
            incoming.release()
        }
    }
}
