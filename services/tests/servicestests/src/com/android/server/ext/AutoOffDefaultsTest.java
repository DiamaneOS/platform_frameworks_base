/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 The DiamaneOS Project
 */

package com.android.server.ext;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;

import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;

import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.ext.settings.ExtSettings;
import android.provider.Settings;
import android.test.mock.MockContentResolver;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.util.test.FakeSettingsProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class AutoOffDefaultsTest {
    private static final int TWELVE_HOURS = (int) HOURS.toMillis(12);
    private static final int TEN_MINUTES = (int) MINUTES.toMillis(10);

    private MockContentResolver mResolver;
    private Context mContext;

    @Before
    public void setUp() {
        FakeSettingsProvider.clearSettingsProvider();
        Context target = getInstrumentation().getTargetContext();
        mResolver = new MockContentResolver(target);
        mResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        mContext = new ContextWrapper(target) {
            @Override
            public ContentResolver getContentResolver() {
                return mResolver;
            }
        };
    }

    @After
    public void tearDown() {
        FakeSettingsProvider.clearSettingsProvider();
    }

    @Test
    public void defaultsWhenNeverSet() {
        assertThat(ExtSettings.AUTO_REBOOT_TIMEOUT.get(mContext)).isEqualTo(TWELVE_HOURS);
        assertThat(ExtSettings.BLUETOOTH_AUTO_OFF.get(mContext)).isEqualTo(TEN_MINUTES);
        assertThat(ExtSettings.NFC_AUTO_OFF.get(mContext)).isEqualTo(TEN_MINUTES);
        assertThat(ExtSettings.WIFI_AUTO_OFF.get(mContext)).isEqualTo(0);
    }

    @Test
    public void storedChoiceWins() {
        int eighteenHours = (int) HOURS.toMillis(18);
        Settings.Global.putInt(mResolver, Settings.Global.AUTO_REBOOT_TIMEOUT, eighteenHours);
        Settings.Global.putInt(mResolver, Settings.Global.BLUETOOTH_AUTO_OFF, 0);
        Settings.Global.putInt(mResolver, Settings.Global.NFC_AUTO_OFF, 0);

        assertThat(ExtSettings.AUTO_REBOOT_TIMEOUT.get(mContext)).isEqualTo(eighteenHours);
        assertThat(ExtSettings.BLUETOOTH_AUTO_OFF.get(mContext)).isEqualTo(0);
        assertThat(ExtSettings.NFC_AUTO_OFF.get(mContext)).isEqualTo(0);
    }
}
