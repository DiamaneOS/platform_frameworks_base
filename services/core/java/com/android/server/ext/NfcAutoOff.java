/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 The DiamaneOS Project
 */

package com.android.server.ext;

import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.ext.settings.ExtSettings;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import android.os.Binder;
import android.os.HandlerExecutor;
import android.os.PowerManager;
import android.util.Slog;

/** Turns NFC off after the NFC_AUTO_OFF timeout. NfcAutoOffRule says when. */
class NfcAutoOff extends DelayedConditionalAction {
    private static final String TAG = NfcAutoOff.class.getSimpleName();

    private final NfcAutoOffRule rule = new NfcAutoOffRule();
    private final PowerManager powerManager;
    @Nullable
    private NfcAdapter adapter;
    @Nullable
    private CardEmulation cardEmulation;

    // A reader's field (payment terminal, transit gate) counts as use.
    private final CardEmulation.NfcEventCallback fieldCallback =
            new CardEmulation.NfcEventCallback() {
                @Override
                public void onRemoteFieldChanged(boolean isDetected) {
                    Slog.d(TAG, "reader field " + isDetected);
                    rule.setFieldPresent(isDetected);
                    update();
                }
            };

    private NfcAutoOff(SystemServerExt sse) {
        super(sse, ExtSettings.NFC_AUTO_OFF, sse.bgHandler);
        powerManager = sse.context.getSystemService(PowerManager.class);
    }

    static void maybeInit(SystemServerExt sse) {
        if (sse.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC, 0)) {
            new NfcAutoOff(sse).init();
        }
    }

    @Override
    protected boolean shouldScheduleAlarm() {
        return rule.restartTimer();
    }

    @Override
    protected void alarmTriggered() {
        if (rule.onTimeUp(powerManager.isInteractive())) {
            turnOff();
        } else {
            Slog.d(TAG, "time is up, waiting for the screen to go off");
        }
    }

    @Override
    protected void registerStateListener() {
        IntentFilter f = new IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED);
        f.addAction(Intent.ACTION_SCREEN_OFF);

        sse.context.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context broadcastContext, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    if (rule.onScreenOff()) {
                        turnOff();
                    }
                    return;
                }
                int state = intent.getIntExtra(NfcAdapter.EXTRA_ADAPTER_STATE,
                        NfcAdapter.STATE_OFF);
                Slog.d(TAG, "adapter state " + state);
                onNfcState(state == NfcAdapter.STATE_ON);
            }
        }, f, null, handler);

        // The NFC service usually starts later and then reports its state with the broadcast.
        NfcAdapter a = getAdapter();
        if (a != null) {
            boolean on = false;
            try {
                on = a.isEnabled();
            } catch (RuntimeException e) {
                Slog.w(TAG, "isEnabled failed", e);
            }
            onNfcState(on);
        }
    }

    private void onNfcState(boolean on) {
        rule.setNfcOn(on);
        if (on) {
            registerFieldCallback();
        }
        update();
    }

    // Registered again each time NFC turns on: CardEmulation follows only one restart of the
    // NFC service, and a new registration makes it watch the current one.
    private void registerFieldCallback() {
        NfcAdapter a = getAdapter();
        if (a == null) {
            return;
        }
        Binder.allowBlockingForCurrentThread();
        try {
            if (cardEmulation == null) {
                cardEmulation = CardEmulation.getInstance(a);
            }
            cardEmulation.unregisterNfcEventCallback(fieldCallback);
            cardEmulation.registerNfcEventCallback(new HandlerExecutor(handler), fieldCallback);
        } catch (RuntimeException e) {
            Slog.w(TAG, "no reader field events, the timer alone applies", e);
        } finally {
            Binder.defaultBlockingForCurrentThread();
        }
    }

    private void turnOff() {
        NfcAdapter a = getAdapter();
        if (a == null) {
            return;
        }
        Slog.d(TAG, "adapter.disable()");
        Binder.allowBlockingForCurrentThread();
        try {
            a.disable();
        } catch (RuntimeException e) {
            // for example a user restriction on changing NFC: try again after the timeout
            Slog.w(TAG, "disable failed", e);
            update();
        } finally {
            Binder.defaultBlockingForCurrentThread();
        }
    }

    @Nullable
    private NfcAdapter getAdapter() {
        if (adapter == null) {
            try {
                adapter = NfcAdapter.getDefaultAdapter(sse.context);
            } catch (RuntimeException e) {
                Slog.w(TAG, "no NFC adapter", e);
            }
        }
        return adapter;
    }

    @Override
    protected String getLogTag() {
        return TAG;
    }
}
