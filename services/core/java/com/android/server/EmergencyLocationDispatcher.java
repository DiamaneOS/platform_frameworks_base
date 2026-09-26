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
package com.android.server;

import android.Manifest;
import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;

/** Supplementary location notification; never waits for location or controls a call. */
final class EmergencyLocationDispatcher {
    static final String CALL = "org.diamaneos.emergencylocation.action.CALL";
    static final String SMS = "org.diamaneos.emergencylocation.action.SMS";
    private static final String PACKAGE = "org.diamaneos.emergencylocation";
    private final Context mContext;
    private final Handler mHandler;
    private int mPending;

    EmergencyLocationDispatcher(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
    }

    // Caller has already checked MODIFY_PHONE_STATE and the modem/phone index.
    synchronized void notify(String action, int phoneId, int subId, String number) {
        if ((!CALL.equals(action) && !SMS.equals(action))
                || phoneId < 0
                || number == null
                || !number.matches("[0-9]{2,6}")
                || mPending >= 8) return;
        final long utc = System.currentTimeMillis();
        final long elapsed = SystemClock.elapsedRealtime();
        mPending++;
        if (!mHandler.post(
                () -> {
                    try {
                        // Do not deliver stale events after a system-server handler backlog.
                        if (SystemClock.elapsedRealtime() - elapsed > 10000) return;
                        final long identity = Binder.clearCallingIdentity();
                        try {
                            Intent event =
                                    new Intent(action)
                                            .setClassName(PACKAGE, PACKAGE + ".EmergencyReceiver")
                                            .putExtra("number", number)
                                            .putExtra("subscription", subId)
                                            .putExtra("phone", phoneId)
                                            .putExtra("utc", utc)
                                            .putExtra("elapsed", elapsed)
                                            .addFlags(
                                                    Intent.FLAG_RECEIVER_FOREGROUND
                                                            | Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                            BroadcastOptions options = BroadcastOptions.makeBasic();
                            options.setShareIdentityEnabled(true);
                            mContext.sendBroadcastAsUser(
                                    event,
                                    UserHandle.SYSTEM,
                                    Manifest.permission.READ_PRIVILEGED_PHONE_STATE,
                                    options.toBundle());
                        } catch (RuntimeException e) {
                            Log.w(
                                    "EmergencyLocation",
                                    "Emergency-location notification unavailable");
                        } finally {
                            Binder.restoreCallingIdentity(identity);
                        }
                    } finally {
                        synchronized (EmergencyLocationDispatcher.this) {
                            mPending--;
                        }
                    }
                })) mPending--;
    }
}
