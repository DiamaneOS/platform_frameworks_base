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

package de.diamaneos.decode.client;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.DecodeGate;
import android.os.Bundle;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The decode gate's provider played in the test process ({@link DecodeGate#setLocal}): the gate's timing, memory and
 * refill rules on a phone without asking the VM. Every request is taken and answered on a thread of its own after
 * {@link #delayMs} (never when it is negative): a probe as a PNG of {@link #width} x {@link #height}, a region or a
 * decode as a red bitmap of what was asked (at most that size), or a refusal of the bytes.
 */
final class FakeGateProvider implements DecodeGate.Local {
    volatile long delayMs;
    volatile int width = 64;
    volatile int height = 32;
    volatile boolean refuse;
    final AtomicInteger calls = new AtomicInteger();

    FakeGateProvider(long delayMs) {
        this.delayMs = delayMs;
    }

    @Override
    public Bundle call(String method, Bundle extras) {
        calls.incrementAndGet();
        // The bytes (EXTRA_FD) are not read: the gate closes its descriptor when the call returns.
        final IRemoteCallback callback = IRemoteCallback.Stub.asInterface(
                extras.getBinder(DecodeGate.EXTRA_CALLBACK));
        final int w = extras.getInt(DecodeGate.EXTRA_WIDTH);
        final int h = extras.getInt(DecodeGate.EXTRA_HEIGHT);
        final int sample = Math.max(1, extras.getInt(DecodeGate.EXTRA_SAMPLE, 1));
        final long delay = delayMs;
        if (delay >= 0) {
            final Thread answer = new Thread(() -> {
                SystemClock.sleep(delay);
                try {
                    callback.sendResult(answer(method, w, h, sample));
                } catch (RemoteException ignored) {
                    // The gate's own process.
                }
            }, "FakeGateAnswer");
            answer.setDaemon(true);
            answer.start();
        }
        final Bundle taken = new Bundle();
        taken.putBoolean(DecodeGate.EXTRA_TAKEN, true);
        return taken;
    }

    private Bundle answer(String method, int w, int h, int sample) {
        final Bundle reply = new Bundle();
        if (refuse) {
            reply.putString(DecodeGate.EXTRA_ERROR, "refused by the test");
            reply.putBoolean(DecodeGate.EXTRA_REFUSED, true);
            return reply;
        }
        if (DecodeGate.METHOD_PROBE.equals(method) || DecodeGate.METHOD_HEADER.equals(method)) {
            reply.putInt(DecodeGate.EXTRA_WIDTH, width);
            reply.putInt(DecodeGate.EXTRA_HEIGHT, height);
            reply.putInt(DecodeGate.EXTRA_FORMAT, 2); // PNG
            return reply;
        }
        final int outW = DecodeGate.METHOD_REGION.equals(method) ? (w + sample - 1) / sample : Math.min(w, width);
        final int outH = DecodeGate.METHOD_REGION.equals(method) ? (h + sample - 1) / sample : Math.min(h, height);
        final Bitmap bitmap = Bitmap.createBitmap(Math.max(1, outW), Math.max(1, outH), Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.RED);
        reply.putParcelable(DecodeGate.EXTRA_BITMAP, bitmap);
        return reply;
    }
}
