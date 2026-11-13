/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 The DiamaneOS Project
 */

package com.android.packageinstaller.test;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.res.Resources;

import com.android.packageinstaller.v2.ui.SpecialRuntimePermUtils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for the check that keeps the Network permission box out of the install dialog: it is true
 * only for the packages the device configuration lists (config_networkPermissionFixedOffPackages),
 * so every other package keeps the box.
 */
@RunWith(JUnit4.class)
public class SpecialRuntimePermUtilsTest {
    private static final String LISTED_PACKAGE = "com.example.listed";
    private static final String OTHER_LISTED_PACKAGE = "com.example.listed.too";
    private static final String UNLISTED_PACKAGE = "com.example.unlisted";

    @Test
    public void listedPackage_isFixedOff() {
        Resources resources = resourcesListing(LISTED_PACKAGE, OTHER_LISTED_PACKAGE);

        assertThat(SpecialRuntimePermUtils.isNetworkPermissionFixedOff(resources, LISTED_PACKAGE))
                .isTrue();
        assertThat(SpecialRuntimePermUtils.isNetworkPermissionFixedOff(resources,
                OTHER_LISTED_PACKAGE)).isTrue();
    }

    @Test
    public void unlistedPackage_isNotFixedOff() {
        Resources resources = resourcesListing(LISTED_PACKAGE, OTHER_LISTED_PACKAGE);

        assertThat(SpecialRuntimePermUtils.isNetworkPermissionFixedOff(resources,
                UNLISTED_PACKAGE)).isFalse();
    }

    @Test
    public void packageNameMustMatchInFull() {
        Resources resources = resourcesListing(LISTED_PACKAGE);

        assertThat(SpecialRuntimePermUtils.isNetworkPermissionFixedOff(resources,
                LISTED_PACKAGE + ".more")).isFalse();
        assertThat(SpecialRuntimePermUtils.isNetworkPermissionFixedOff(resources, "com.example"))
                .isFalse();
    }

    @Test
    public void emptyList_noPackageIsFixedOff() {
        Resources resources = resourcesListing();

        assertThat(SpecialRuntimePermUtils.isNetworkPermissionFixedOff(resources, LISTED_PACKAGE))
                .isFalse();
    }

    /** Resources whose list of packages with the Network permission fixed off is the given one. */
    private static Resources resourcesListing(String... packageNames) {
        Resources resources = mock(Resources.class);
        when(resources.getStringArray(
                com.android.internal.R.array.config_networkPermissionFixedOffPackages))
                .thenReturn(packageNames);
        return resources;
    }
}
