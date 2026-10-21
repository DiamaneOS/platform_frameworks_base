/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.locksettings;

import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_TIMEOUT;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN;

import static junit.framework.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.io.FileDescriptor.err;
import static java.io.FileDescriptor.in;
import static java.io.FileDescriptor.out;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.widget.LockCredentialPolicy;
import com.android.internal.widget.LockPatternUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class LockSettingsShellCommandCredentialPolicyTest {

    private LockSettingsShellCommand mCommand;

    private @Mock LockPatternUtils mLockPatternUtils;
    private @Mock LockSettingsShellCommand.LearningPeriodSetter mLearningPeriodSetter;
    private int mUserId;
    private final Binder mBinder = new Binder();
    private final ShellCallback mShellCallback = new ShellCallback();
    private final ResultReceiver mResultReceiver = new ResultReceiver(
            new Handler(Looper.getMainLooper()));

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mUserId = ActivityManager.getCurrentUser();
        mCommand = newCommand(/* debuggable= */ true);
        when(mLockPatternUtils.hasSecureLockScreen()).thenReturn(true);
    }

    private LockSettingsShellCommand newCommand(boolean debuggable) {
        final Context context = InstrumentationRegistry.getTargetContext();
        return new LockSettingsShellCommand(mLockPatternUtils, context, 0, Process.SHELL_UID,
                debuggable, mLearningPeriodSetter);
    }

    private int exec(LockSettingsShellCommand command, String... args) {
        return command.exec(new Binder(), in, out, err, args, mShellCallback, mResultReceiver);
    }

    @Test
    public void testRequireStrongAuth_STRONG_AUTH_REQUIRED_AFTER_TIMEOUT() throws Exception {
        assertEquals(0, exec(mCommand,
                "require-strong-auth", "STRONG_AUTH_REQUIRED_AFTER_TIMEOUT"));

        // Of the current user only, unlike a lockdown.
        verify(mLockPatternUtils).requireStrongAuth(STRONG_AUTH_REQUIRED_AFTER_TIMEOUT, mUserId);
    }

    @Test
    public void testRequireStrongAuth_STRONG_AUTH_REQUIRED_AFTER_TIMEOUT_forUser()
            throws Exception {
        assertEquals(0, exec(mCommand,
                "require-strong-auth", "--user", "10", "STRONG_AUTH_REQUIRED_AFTER_TIMEOUT"));

        verify(mLockPatternUtils).requireStrongAuth(STRONG_AUTH_REQUIRED_AFTER_TIMEOUT, 10);
    }

    @Test
    public void testRequireStrongAuth_STRONG_AUTH_REQUIRED_AFTER_TIMEOUT_notDebuggable()
            throws Exception {
        exec(newCommand(/* debuggable= */ false),
                "require-strong-auth", "STRONG_AUTH_REQUIRED_AFTER_TIMEOUT");

        verify(mLockPatternUtils, never()).requireStrongAuth(anyInt(), anyInt());
    }

    @Test
    public void testRequireStrongAuth_STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN_notDebuggable()
            throws Exception {
        assertEquals(0, exec(newCommand(/* debuggable= */ false),
                "require-strong-auth", "STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN"));

        verify(mLockPatternUtils).requireStrongAuth(
                STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN,
                UserHandle.USER_ALL);
    }

    @Test
    public void testSetLearningRemaining() throws Exception {
        when(mLearningPeriodSetter.setLearningPeriodRemaining(21600000L, mUserId))
                .thenReturn(true);

        assertEquals(0, exec(mCommand, "set-learning-remaining", "21600000"));

        verify(mLearningPeriodSetter).setLearningPeriodRemaining(21600000L, mUserId);
    }

    @Test
    public void testSetLearningRemaining_forUser() throws Exception {
        when(mLearningPeriodSetter.setLearningPeriodRemaining(0L, 10)).thenReturn(true);

        assertEquals(0, exec(mCommand, "set-learning-remaining", "--user", "10", "0"));

        verify(mLearningPeriodSetter).setLearningPeriodRemaining(0L, 10);
    }

    @Test
    public void testSetLearningRemaining_noLearningPeriod() throws Exception {
        when(mLearningPeriodSetter.setLearningPeriodRemaining(21600000L, mUserId))
                .thenReturn(false);

        assertEquals(-1, exec(mCommand, "set-learning-remaining", "21600000"));
    }

    @Test
    public void testSetLearningRemaining_outOfRange() throws Exception {
        final String tooLong = Long.toString(LockCredentialPolicy.LEARNING_PERIOD_MILLIS + 1);

        assertEquals(-1, exec(newCommand(/* debuggable= */ true),
                "set-learning-remaining", tooLong));
        // Read as an unknown option.
        assertEquals(-1, exec(newCommand(/* debuggable= */ true),
                "set-learning-remaining", "-1"));
        assertEquals(-1, exec(newCommand(/* debuggable= */ true),
                "set-learning-remaining", "soon"));
        assertEquals(-1, exec(newCommand(/* debuggable= */ true), "set-learning-remaining"));

        verify(mLearningPeriodSetter, never()).setLearningPeriodRemaining(anyLong(), anyInt());
    }

    @Test
    public void testSetLearningRemaining_notDebuggable() throws Exception {
        // Refused in the way of a command that does not exist.
        assertEquals(
                exec(newCommand(/* debuggable= */ false), "no-such-command", "21600000"),
                exec(newCommand(/* debuggable= */ false), "set-learning-remaining", "21600000"));

        verify(mLearningPeriodSetter, never()).setLearningPeriodRemaining(anyLong(), anyInt());
    }
}
