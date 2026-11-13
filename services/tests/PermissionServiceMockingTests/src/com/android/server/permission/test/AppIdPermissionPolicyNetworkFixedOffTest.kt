/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 The DiamaneOS Project
 */

package com.android.server.permission.test

import android.Manifest
import android.content.pm.PermissionInfo
import com.android.server.permission.access.permission.PermissionFlags
import com.android.server.pm.pkg.PackageState
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Test

/**
 * Tests for the Network permission that the device configuration fixes off for listed packages
 * (config_networkPermissionFixedOffPackages) in AppIdPermissionPolicy: never granted, flagged as
 * fixed by the system, and left alone for every other package and permission.
 */
class AppIdPermissionPolicyNetworkFixedOffTest : BasePermissionPolicyTest() {
    private val internetPermission =
        mockParsedPermission(
            PERMISSION_INTERNET,
            PACKAGE_NAME_0,
            protectionLevel = PermissionInfo.PROTECTION_DANGEROUS,
        )
    private val otherPermission =
        mockParsedPermission(
            PERMISSION_NAME_1,
            PACKAGE_NAME_0,
            protectionLevel = PermissionInfo.PROTECTION_DANGEROUS,
        )

    @Before
    fun setUp() {
        val permissionOwnerPackageState =
            mockPackageState(
                APP_ID_0,
                mockAndroidPackage(
                    PACKAGE_NAME_0,
                    permissions = listOf(internetPermission, otherPermission),
                ),
            )
        addPackageState(permissionOwnerPackageState)
        addPermission(internetPermission)
        addPermission(otherPermission)
        // PACKAGE_NAME_1 is the listed package, PACKAGE_NAME_2 is not listed.
        oldState.mutateExternalState().setNetworkPermissionFixedOffPackages(setOf(PACKAGE_NAME_1))
    }

    @Test
    fun testOnPackageAdded_listedPackageNewInstall_isFixedOff() {
        val packageState = mockRequestingPackageState(APP_ID_1, PACKAGE_NAME_1)

        mutateState {
            addPackageState(packageState, newState)
            with(appIdPermissionPolicy) { onPackageAdded(packageState) }
        }

        assertFixedOff(APP_ID_1, USER_ID_0, "onPackageAdded() for a newly installed listed package")
    }

    @Test
    fun testOnPackageAdded_listedSystemPackage_isFixedOff() {
        // A system package gets the Network permission granted by the evaluation otherwise.
        val packageState = mockRequestingPackageState(APP_ID_1, PACKAGE_NAME_1, isSystem = true)

        mutateState {
            addPackageState(packageState, newState)
            with(appIdPermissionPolicy) { onPackageAdded(packageState) }
        }

        assertFixedOff(APP_ID_1, USER_ID_0, "onPackageAdded() for a listed system package")
    }

    @Test
    fun testOnStorageVolumeMounted_listedPackageWasGranted_grantIsRemoved() {
        val packageState = mockRequestingPackageState(APP_ID_1, PACKAGE_NAME_1)
        addPackageState(packageState)
        val oldFlags =
            PermissionFlags.RUNTIME_GRANTED or
                PermissionFlags.USER_SET or
                PermissionFlags.PREGRANT or
                USER_SENSITIVE_FLAGS
        setPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_INTERNET, oldFlags)

        mutateState {
            with(appIdPermissionPolicy) {
                onStorageVolumeMounted(null, listOf(PACKAGE_NAME_1), true)
            }
        }

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_INTERNET)
        val expectedNewFlags = PermissionFlags.SYSTEM_FIXED or USER_SENSITIVE_FLAGS
        assertWithMessage(
                "After onStorageVolumeMounted() is called for a listed package that had the" +
                    " Network permission granted, the actual permission flags $actualFlags should" +
                    " match the expected flags $expectedNewFlags"
            )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
    }

    @Test
    fun testOnUserAdded_listedPackage_isFixedOff() {
        val packageState = mockRequestingPackageState(APP_ID_1, PACKAGE_NAME_1)
        addPackageState(packageState)
        createUserState(USER_ID_NEW)

        mutateState { with(appIdPermissionPolicy) { onUserAdded(USER_ID_NEW) } }

        assertFixedOff(APP_ID_1, USER_ID_NEW, "onUserAdded() with a listed package installed")
    }

    @Test
    fun testUpdatePermissionFlags_listedPackageGrant_isRefused() {
        val packageState = mockRequestingPackageState(APP_ID_1, PACKAGE_NAME_1)
        addPackageState(packageState)
        setPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_INTERNET, PermissionFlags.SYSTEM_FIXED)

        var isChanged = true
        mutateState {
            with(appIdPermissionPolicy) {
                // What a grant through PackageManager.grantRuntimePermission() ends in.
                isChanged =
                    updatePermissionFlags(
                        APP_ID_1,
                        USER_ID_0,
                        PERMISSION_INTERNET,
                        PermissionFlags.RUNTIME_GRANTED or PermissionFlags.USER_SET,
                        PermissionFlags.RUNTIME_GRANTED or PermissionFlags.USER_SET,
                    )
            }
        }

        assertWithMessage("A grant for a listed package should not change the permission state")
            .that(isChanged)
            .isFalse()
        assertFixedOff(APP_ID_1, USER_ID_0, "a grant for a listed package")
    }

    @Test
    fun testSetPermissionFlags_listedPackageUnfixAndGrant_isRefused() {
        val packageState = mockRequestingPackageState(APP_ID_1, PACKAGE_NAME_1)
        addPackageState(packageState)
        setPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_INTERNET, PermissionFlags.SYSTEM_FIXED)

        mutateState {
            with(appIdPermissionPolicy) {
                // Not even a caller that may change system flags gets past the fixed state.
                setPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_INTERNET, 0)
                setPermissionFlags(
                    APP_ID_1,
                    USER_ID_0,
                    PERMISSION_INTERNET,
                    PermissionFlags.RUNTIME_GRANTED or
                        PermissionFlags.LEGACY_GRANTED or
                        PermissionFlags.IMPLICIT_GRANTED or
                        PermissionFlags.POLICY_FIXED,
                )
            }
        }

        assertFixedOff(APP_ID_1, USER_ID_0, "removing the fixed flag and granting")
    }

    @Test
    fun testOnPackageAdded_listedPackageOtherPermission_remainsUnchanged() {
        val packageState =
            mockRequestingPackageState(
                APP_ID_1,
                PACKAGE_NAME_1,
                requestedPermissions = setOf(PERMISSION_INTERNET, PERMISSION_NAME_1),
            )
        addPackageState(packageState)
        val oldFlags = PermissionFlags.RUNTIME_GRANTED or PermissionFlags.USER_SET
        setPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_1, oldFlags)

        mutateState { with(appIdPermissionPolicy) { onPackageAdded(packageState) } }

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_NAME_1)
        assertWithMessage(
                "After onPackageAdded() is called for a listed package, the actual flags" +
                    " $actualFlags of another permission should match the old flags $oldFlags"
            )
            .that(actualFlags)
            .isEqualTo(oldFlags)
        assertFixedOff(APP_ID_1, USER_ID_0, "onPackageAdded() for a listed package")
    }

    @Test
    fun testOnPackageAdded_unlistedPackageWasGranted_remainsUnchanged() {
        val packageState = mockRequestingPackageState(APP_ID_2, PACKAGE_NAME_2)
        addPackageState(packageState)
        val oldFlags = PermissionFlags.RUNTIME_GRANTED or PermissionFlags.USER_SET
        setPermissionFlags(APP_ID_2, USER_ID_0, PERMISSION_INTERNET, oldFlags)

        mutateState { with(appIdPermissionPolicy) { onPackageAdded(packageState) } }

        val actualFlags = getPermissionFlags(APP_ID_2, USER_ID_0, PERMISSION_INTERNET)
        assertWithMessage(
                "After onPackageAdded() is called for a package that is not listed, the actual" +
                    " permission flags $actualFlags should match the old flags $oldFlags"
            )
            .that(actualFlags)
            .isEqualTo(oldFlags)
    }

    @Test
    fun testUpdatePermissionFlags_unlistedPackageGrantAndRevoke_isApplied() {
        val packageState = mockRequestingPackageState(APP_ID_2, PACKAGE_NAME_2)
        addPackageState(packageState)

        mutateState {
            with(appIdPermissionPolicy) {
                onPackageAdded(packageState)
                updatePermissionFlags(
                    APP_ID_2,
                    USER_ID_0,
                    PERMISSION_INTERNET,
                    PermissionFlags.RUNTIME_GRANTED,
                    PermissionFlags.RUNTIME_GRANTED,
                )
            }
        }
        val grantedFlags = getPermissionFlags(APP_ID_2, USER_ID_0, PERMISSION_INTERNET)
        assertWithMessage(
                "After a grant for a package that is not listed, the actual permission flags" +
                    " $grantedFlags should match the expected flags" +
                    " ${PermissionFlags.RUNTIME_GRANTED}"
            )
            .that(grantedFlags)
            .isEqualTo(PermissionFlags.RUNTIME_GRANTED)

        oldState = newState
        mutateState {
            with(appIdPermissionPolicy) {
                updatePermissionFlags(
                    APP_ID_2,
                    USER_ID_0,
                    PERMISSION_INTERNET,
                    PermissionFlags.RUNTIME_GRANTED,
                    0,
                )
            }
        }
        val revokedFlags = getPermissionFlags(APP_ID_2, USER_ID_0, PERMISSION_INTERNET)
        assertWithMessage(
                "After a revoke for a package that is not listed, the actual permission flags" +
                    " $revokedFlags should be empty"
            )
            .that(revokedFlags)
            .isEqualTo(0)
    }

    @Test
    fun testOnPackageAdded_packageSharingListedAppId_isFixedOff() {
        // Permission state is per app ID, so the listed package decides for the whole app ID.
        val listedPackageState =
            mockRequestingPackageState(APP_ID_1, PACKAGE_NAME_1, requestedPermissions = emptySet())
        val sharingPackageState = mockRequestingPackageState(APP_ID_1, PACKAGE_NAME_2)
        addPackageState(listedPackageState)
        addPackageState(sharingPackageState)
        val oldFlags = PermissionFlags.RUNTIME_GRANTED
        setPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_INTERNET, oldFlags)

        mutateState { with(appIdPermissionPolicy) { onPackageAdded(sharingPackageState) } }

        assertFixedOff(APP_ID_1, USER_ID_0, "onPackageAdded() for a package sharing the app ID")
    }

    @Test
    fun testOnPackageAdded_listedPackageNoLongerRequests_stateIsCleared() {
        val packageState =
            mockRequestingPackageState(APP_ID_1, PACKAGE_NAME_1, requestedPermissions = emptySet())
        addPackageState(packageState)
        setPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_INTERNET, PermissionFlags.SYSTEM_FIXED)

        mutateState { with(appIdPermissionPolicy) { onPackageAdded(packageState) } }

        val actualFlags = getPermissionFlags(APP_ID_1, USER_ID_0, PERMISSION_INTERNET)
        assertWithMessage(
                "After onPackageAdded() is called for a listed package that no longer requests" +
                    " the Network permission, the actual permission flags $actualFlags should be" +
                    " empty"
            )
            .that(actualFlags)
            .isEqualTo(0)
    }

    private fun mockRequestingPackageState(
        appId: Int,
        packageName: String,
        requestedPermissions: Set<String> = setOf(PERMISSION_INTERNET),
        isSystem: Boolean = false,
    ): PackageState =
        mockPackageState(
            appId,
            mockAndroidPackage(packageName, requestedPermissions = requestedPermissions),
            isSystem = isSystem,
        )

    private fun assertFixedOff(appId: Int, userId: Int, after: String) {
        val actualFlags = getPermissionFlags(appId, userId, PERMISSION_INTERNET)
        val expectedNewFlags = PermissionFlags.SYSTEM_FIXED
        assertWithMessage(
                "After $after, the actual Network permission flags $actualFlags should match the" +
                    " expected flags $expectedNewFlags"
            )
            .that(actualFlags)
            .isEqualTo(expectedNewFlags)
        assertWithMessage("After $after, the Network permission should not be granted")
            .that(PermissionFlags.isPermissionGranted(actualFlags))
            .isFalse()
    }

    companion object {
        private const val PERMISSION_INTERNET = Manifest.permission.INTERNET

        private const val APP_ID_2 = 3

        private const val USER_SENSITIVE_FLAGS =
            PermissionFlags.USER_SENSITIVE_WHEN_GRANTED or
                PermissionFlags.USER_SENSITIVE_WHEN_REVOKED
    }
}
