/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.clipboardoverlay;

import static com.google.android.setupcompat.util.WizardManagerHelper.SETTINGS_SECURE_USER_SETUP_COMPLETE;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.settings.FakeUserTracker;
import com.android.systemui.user.utils.FakeUserScopedService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.util.List;

import javax.inject.Provider;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ClipboardListenerQuietCopyTest extends SysuiTestCase {

    @Mock
    private ClipboardManager mClipboardManager;
    @Mock
    private KeyguardManager mKeyguardManager;
    @Mock
    private ClipboardManager mClipboardManagerSecondaryUser;
    @Mock
    private KeyguardManager mKeyguardManagerSecondaryUser;
    @Mock
    private ClipboardOverlayController mOverlayController;
    @Mock
    private ClipboardToast mClipboardToast;
    @Mock
    private UiEventLogger mUiEventLogger;
    @Mock
    private ClipboardOverlaySuppressionController mClipboardOverlaySuppressionController;

    @Captor
    private ArgumentCaptor<Runnable> mRunnableCaptor;
    @Captor
    private ArgumentCaptor<ClipData> mClipDataCaptor;
    @Captor
    private ArgumentCaptor<String> mStringCaptor;

    @Spy
    private Provider<ClipboardOverlayController> mOverlayControllerProvider;

    private final FakeUserScopedService<ClipboardManager> mUserScopedClipboardManager =
            new FakeUserScopedService<>(mClipboardManager);
    private final FakeUserScopedService<KeyguardManager> mUserScopedKeyguardManager =
            new FakeUserScopedService<>(mKeyguardManager);
    private final FakeUserTracker mUserTracker = new FakeUserTracker();

    private final List<UserInfo> mUserInfos = List.of(
            new UserInfo(0, "system", 0), new UserInfo(50, "secondary", 0));
    private final ClipData mSampleClipData = new ClipData("Test", new String[]{"text/plain"},
            new ClipData.Item("Test Item"));
    private final ClipData mSecondaryClipData = new ClipData(
            "Test secondary", new String[]{"text/plain"}, new ClipData.Item("Secondary Item"));
    private final String mSampleSource = "Example source";

    private ClipboardListener mClipboardListener;

    @Before
    public void setup() {
        mOverlayControllerProvider = () -> mOverlayController;

        MockitoAnnotations.initMocks(this);

        Settings.Secure.putInt(
                mContext.getContentResolver(), SETTINGS_SECURE_USER_SETUP_COMPLETE, 1);

        mUserTracker.set(mUserInfos, 0);
        UserHandle user0 = mUserInfos.get(0).getUserHandle();
        UserHandle user1 = mUserInfos.get(1).getUserHandle();
        mUserScopedKeyguardManager.addImplementation(user0, mKeyguardManager);
        mUserScopedKeyguardManager.addImplementation(user1, mKeyguardManagerSecondaryUser);
        setupClipboardManager(mClipboardManager, user0, mSampleClipData);
        setupClipboardManager(mClipboardManagerSecondaryUser, user1, mSecondaryClipData);

        mClipboardListener = new ClipboardListener(
                getContext(),
                mOverlayControllerProvider,
                mClipboardToast,
                mUserTracker,
                mUserScopedClipboardManager,
                mUserScopedKeyguardManager,
                mUiEventLogger,
                getContext().getMainExecutor(),
                mClipboardOverlaySuppressionController);
    }

    private void setupClipboardManager(
            ClipboardManager clipboardManager, UserHandle user, ClipData clipData) {
        when(clipboardManager.hasPrimaryClip()).thenReturn(true);
        when(clipboardManager.getPrimaryClip()).thenReturn(clipData);
        when(clipboardManager.getPrimaryClipSource()).thenReturn(mSampleSource);
        mUserScopedClipboardManager.addImplementation(user, clipboardManager);
    }

    private static ClipData quietCopyClip(boolean marker) {
        ClipDescription desc = new ClipDescription("Screenshot", new String[]{"image/png"});
        PersistableBundle bundle = new PersistableBundle();
        bundle.putBoolean(ClipboardListener.EXTRA_QUIET_COPY, marker);
        desc.setExtras(bundle);
        return new ClipData(desc, new ClipData.Item("Test Item"));
    }

    // Every package resolves with the given flags, so only the package name check can reject.
    private static PackageManager packageManagerWithFlags(int flags) throws Exception {
        PackageManager pm = mock(PackageManager.class);
        ApplicationInfo info = new ApplicationInfo();
        info.flags = flags;
        when(pm.getApplicationInfoAsUser(anyString(), anyInt(), anyInt())).thenReturn(info);
        return pm;
    }

    @Test
    public void test_isQuietCopy_systemCanvasWithMarker_returnsTrue() throws Exception {
        PackageManager pm = packageManagerWithFlags(ApplicationInfo.FLAG_SYSTEM);

        assertTrue(ClipboardListener.isQuietCopy(quietCopyClip(true),
                ClipboardListener.CANVAS_PACKAGE, pm, 0));
        verify(pm).getApplicationInfoAsUser(eq(ClipboardListener.CANVAS_PACKAGE), anyInt(),
                eq(0));
    }

    @Test
    public void test_isQuietCopy_markerFromOtherApp_returnsFalse() throws Exception {
        PackageManager pm = packageManagerWithFlags(ApplicationInfo.FLAG_SYSTEM);

        assertFalse(ClipboardListener.isQuietCopy(quietCopyClip(true), mSampleSource, pm, 0));
        assertFalse(ClipboardListener.isQuietCopy(quietCopyClip(true),
                ClipboardListener.SHELL_PACKAGE, pm, 0));
        assertFalse(ClipboardListener.isQuietCopy(quietCopyClip(true),
                ClipboardListener.SYSTEMUI_PACKAGE, pm, 0));
        assertFalse(ClipboardListener.isQuietCopy(quietCopyClip(true), null, pm, 0));
    }

    @Test
    public void test_isQuietCopy_canvasWithoutMarker_returnsFalse() throws Exception {
        PackageManager pm = packageManagerWithFlags(ApplicationInfo.FLAG_SYSTEM);

        assertFalse(ClipboardListener.isQuietCopy(mSampleClipData,
                ClipboardListener.CANVAS_PACKAGE, pm, 0));
        assertFalse(ClipboardListener.isQuietCopy(quietCopyClip(false),
                ClipboardListener.CANVAS_PACKAGE, pm, 0));
        assertFalse(ClipboardListener.isQuietCopy(null, ClipboardListener.CANVAS_PACKAGE, pm, 0));
    }

    @Test
    public void test_isQuietCopy_canvasNotSystemApp_returnsFalse() throws Exception {
        assertFalse(ClipboardListener.isQuietCopy(quietCopyClip(true),
                ClipboardListener.CANVAS_PACKAGE, packageManagerWithFlags(0), 0));

        PackageManager missing = mock(PackageManager.class);
        when(missing.getApplicationInfoAsUser(anyString(), anyInt(), anyInt()))
                .thenThrow(new PackageManager.NameNotFoundException());
        assertFalse(ClipboardListener.isQuietCopy(quietCopyClip(true),
                ClipboardListener.CANVAS_PACKAGE, missing, 0));
    }

    @Test
    public void test_onPrimaryClipChanged_quietCopyFromSystemCanvas_showsNothing()
            throws Exception {
        mContext.setMockPackageManager(packageManagerWithFlags(ApplicationInfo.FLAG_SYSTEM));
        when(mClipboardManager.getPrimaryClip()).thenReturn(quietCopyClip(true));
        when(mClipboardManager.getPrimaryClipSource())
                .thenReturn(ClipboardListener.CANVAS_PACKAGE);

        mClipboardListener.start();
        mClipboardListener.onPrimaryClipChanged();

        verifyNoMoreInteractions(mOverlayControllerProvider);
        verifyNoMoreInteractions(mClipboardToast);
    }

    @Test
    public void test_onPrimaryClipChanged_quietMarkerFromOtherApp_showsOverlay()
            throws Exception {
        mContext.setMockPackageManager(packageManagerWithFlags(ApplicationInfo.FLAG_SYSTEM));
        ClipData clip = quietCopyClip(true);
        when(mClipboardManager.getPrimaryClip()).thenReturn(clip);

        mClipboardListener.start();
        mClipboardListener.onPrimaryClipChanged();

        verify(mOverlayController).setClipData(clip, mSampleSource);
    }

    @Test
    public void test_onPrimaryClipChanged_canvasWithoutMarker_showsOverlay() throws Exception {
        mContext.setMockPackageManager(packageManagerWithFlags(ApplicationInfo.FLAG_SYSTEM));
        when(mClipboardManager.getPrimaryClipSource())
                .thenReturn(ClipboardListener.CANVAS_PACKAGE);

        mClipboardListener.start();
        mClipboardListener.onPrimaryClipChanged();

        verify(mOverlayController).setClipData(mSampleClipData, ClipboardListener.CANVAS_PACKAGE);
    }
}
