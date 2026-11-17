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

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The threads that wait for the VM for notification images in SystemUI, so the notification inflation thread never
 * does. Work runs per app in order and at most one task per app at a time, and apps take turns: one app's slow
 * provider or many images hold one of these threads, never the others or the inflation of other apps' notifications.
 * Each task is bounded by its notification's budget and the app's {@link OwnerQuota}.
 *
 * <p>Images of the app's own layouts are decoded only on these threads. Elsewhere (the inflation thread, the main
 * thread) they come from what these threads made, or are empty.
 */
public final class ImageThreads {
    private static final String TAG = "VmDecode";
    private static final int THREADS = 3;

    private static final Object sLock = new Object();
    private static final List<Handler> sIdle = new ArrayList<>(); // guarded by sLock
    private static int sStarted; // guarded by sLock
    // Pending work per app, in the order the apps first asked. An app's queue is never run on two threads at once.
    private static final Map<String, ArrayDeque<Runnable>> sQueues = new LinkedHashMap<>(); // guarded by sLock
    private static final Set<String> sRunning = new HashSet<>(); // guarded by sLock

    private ImageThreads() {
    }

    /** A thread of this pool. */
    private static final class ImageThread extends HandlerThread {
        ImageThread(int n) {
            super("VmImages-" + n, Process.THREAD_PRIORITY_BACKGROUND);
        }
    }

    /** True on one of these threads: the only place the images of an app's layouts may wait for the VM. */
    public static boolean isImageThread() {
        return Thread.currentThread() instanceof ImageThread;
    }

    /** Runs {@code task} on an image thread, after the earlier tasks of {@code owner}. */
    public static void execute(String owner, Runnable task) {
        final String key = owner == null ? "" : owner;
        synchronized (sLock) {
            sQueues.computeIfAbsent(key, k -> new ArrayDeque<>()).add(task);
            dispatchLocked();
        }
    }

    private static void dispatchLocked() {
        for (Iterator<Map.Entry<String, ArrayDeque<Runnable>>> it = sQueues.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<String, ArrayDeque<Runnable>> entry = it.next();
            if (entry.getValue().isEmpty()) {
                if (!sRunning.contains(entry.getKey())) {
                    it.remove();
                }
                continue;
            }
            if (sRunning.contains(entry.getKey())) {
                continue;
            }
            final Handler handler = idleHandlerLocked();
            if (handler == null) {
                return;
            }
            final String owner = entry.getKey();
            final Runnable task = entry.getValue().poll();
            sRunning.add(owner);
            // The app goes to the back of the line, so apps take turns.
            it.remove();
            sQueues.put(owner, entry.getValue());
            handler.post(() -> run(handler, owner, task));
            dispatchLocked();
            return;
        }
    }

    private static Handler idleHandlerLocked() {
        if (!sIdle.isEmpty()) {
            return sIdle.remove(sIdle.size() - 1);
        }
        if (sStarted < THREADS) {
            final ImageThread thread = new ImageThread(sStarted++);
            thread.start();
            return new Handler(thread.getLooper());
        }
        return null;
    }

    private static void run(Handler handler, String owner, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            Log.e(TAG, "notification image task failed for " + owner, e);
        } finally {
            synchronized (sLock) {
                sRunning.remove(owner);
                sIdle.add(handler);
                dispatchLocked();
            }
        }
    }
}
