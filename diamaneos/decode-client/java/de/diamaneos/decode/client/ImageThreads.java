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

import android.os.Process;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The threads that wait for the VM for notification images in SystemUI, so the notification inflation thread never
 * does. Two lanes: the normal one (three threads) and an urgent one (two threads) for calls, alarms and other urgent
 * notifications, which never queue behind normal image work. In each lane work runs per app in order and at most one
 * task per app at a time, and apps take turns: one app's slow provider or many images hold one thread of its lane,
 * never the others. Each task is bounded by its budget (wall-clock time, checked in every loop of the work) and the
 * app's {@link OwnerQuota}, reserved before the task and settled after it: an app whose tasks overrun waits its debt
 * out. The urgent lane is only for what an app cannot claim for itself (see {@link NotificationImageGate#lane}). A
 * task that is cancelled before it runs is dropped ({@link Task#cancel}), a running one stops at its next check.
 *
 * <p>Images of the app's own layouts are decoded only on these threads. Elsewhere (the inflation thread, the main
 * thread) they come from what these threads made, or are empty.
 */
public final class ImageThreads {
    private static final String TAG = "VmDecode";

    private static final Lane NORMAL = new Lane("VmImages-", 3);
    private static final Lane URGENT = new Lane("VmImagesUrgent-", 2);

    private ImageThreads() {
    }

    /** A queued piece of work. */
    public static final class Task {
        private final Lane mLane;
        private final String mOwner;
        private final Runnable mRunnable;

        private Task(Lane lane, String owner, Runnable runnable) {
            mLane = lane;
            mOwner = owner;
            mRunnable = runnable;
        }

        /** Drops the task if it has not started. A running task stops at its next check of its own signal. */
        public void cancel() {
            mLane.remove(this);
        }
    }

    /** A thread of this pool. */
    private static final class ImageThread extends Thread {
        private final Lane mLane;

        ImageThread(Lane lane, String name) {
            super(name);
            setDaemon(true);
            mLane = lane;
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            while (true) {
                final Task task = mLane.take();
                try {
                    task.mRunnable.run();
                } catch (RuntimeException e) {
                    Log.e(TAG, "notification image task failed for " + task.mOwner, e);
                } catch (Error e) {
                    // A stack overflow or out of memory in one task: the thread goes on with the next one.
                    Log.e(TAG, "notification image task failed for " + task.mOwner, e);
                } finally {
                    mLane.done(task);
                }
            }
        }
    }

    /** True on one of these threads: the only place the images of an app's layouts may wait for the VM. */
    public static boolean isImageThread() {
        return Thread.currentThread() instanceof ImageThread;
    }

    /** Tasks one app may have waiting in a lane, and all apps together: past that, a new task is refused. */
    public static final int MAX_QUEUED_PER_OWNER = 32;
    public static final int MAX_QUEUED = 256;

    /**
     * Runs {@code task} on a normal image thread, after the earlier tasks of {@code owner}. Null when the task is
     * refused because too many are waiting (for that app, or for all): the caller goes on without the image thread.
     */
    public static Task execute(String owner, Runnable task) {
        return NORMAL.add(owner, task);
    }

    /** Like {@link #execute}, in the urgent lane, which normal image work never holds up. */
    public static Task executeUrgent(String owner, Runnable task) {
        return URGENT.add(owner, task);
    }

    /** One lane: its threads, pending work per app, and the apps running now. */
    private static final class Lane {
        private final String mName;
        private final int mThreads;
        private int mStarted; // guarded by this
        private int mIdle; // guarded by this
        private int mQueued; // tasks waiting, guarded by this
        // Pending work per app, in the order the apps first asked.
        private final Map<String, ArrayDeque<Task>> mQueues = new LinkedHashMap<>(); // guarded by this
        private final Set<String> mRunning = new HashSet<>(); // guarded by this

        Lane(String name, int threads) {
            mName = name;
            mThreads = threads;
        }

        Task add(String owner, Runnable runnable) {
            final Task task = new Task(this, owner == null ? "" : owner, runnable);
            int start = -1;
            synchronized (this) {
                final ArrayDeque<Task> queue = mQueues.get(task.mOwner);
                if ((queue != null && queue.size() >= MAX_QUEUED_PER_OWNER) || mQueued >= MAX_QUEUED) {
                    Log.w(TAG, "too many image tasks waiting, refused for " + task.mOwner);
                    return null;
                }
                mQueues.computeIfAbsent(task.mOwner, k -> new ArrayDeque<>()).add(task);
                mQueued++;
                if (mIdle == 0 && mStarted < mThreads) {
                    start = mStarted++;
                } else {
                    notifyAll();
                }
            }
            if (start >= 0) {
                // Started outside the lock and not waited for: the caller (often the main thread) never stalls.
                new ImageThread(this, mName + start).start();
            }
            return task;
        }

        synchronized void remove(Task task) {
            final ArrayDeque<Task> queue = mQueues.get(task.mOwner);
            if (queue != null && queue.remove(task)) {
                mQueued--;
            }
        }

        // The next task of an app that is not running, apps taking turns. Waits while there is none.
        synchronized Task take() {
            while (true) {
                for (Iterator<Map.Entry<String, ArrayDeque<Task>>> it = mQueues.entrySet().iterator();
                        it.hasNext(); ) {
                    final Map.Entry<String, ArrayDeque<Task>> entry = it.next();
                    final String owner = entry.getKey();
                    if (entry.getValue().isEmpty()) {
                        if (!mRunning.contains(owner)) {
                            it.remove();
                        }
                        continue;
                    }
                    if (mRunning.contains(owner)) {
                        continue;
                    }
                    final ArrayDeque<Task> queue = entry.getValue();
                    final Task task = queue.poll();
                    mQueued--;
                    mRunning.add(owner);
                    // The app goes to the back of the line.
                    it.remove();
                    mQueues.put(owner, queue);
                    return task;
                }
                mIdle++;
                try {
                    wait();
                } catch (InterruptedException ignored) {
                    // Look again.
                } finally {
                    mIdle--;
                }
            }
        }

        synchronized void done(Task task) {
            mRunning.remove(task.mOwner);
            notifyAll();
        }
    }
}
