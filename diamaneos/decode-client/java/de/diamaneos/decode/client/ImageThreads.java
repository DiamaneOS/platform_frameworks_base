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
import android.os.Looper;
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
 * never the others. Waiting is fair too: an app may queue as many tasks as there is room for, and when a lane is
 * full, a new task of an app takes the place of the newest task of the app with the most waiting (which is told,
 * {@code onDropped}), so no single app can keep the others out. That is fairness by app (package name): apps that
 * work together can still fill a lane between them. Each task is bounded by its budget
 * (wall-clock time, checked in every loop of the work) and the app's {@link OwnerQuota}, reserved before the task and
 * settled after it: an app whose tasks overrun waits its debt out. The urgent lane is only for what an app cannot
 * claim for itself (see {@link NotificationImageGate#lane}). A task that is cancelled before it runs is dropped
 * ({@link Task#cancel}), a running one stops at its next check.
 *
 * <p>Images of the app's own layouts are decoded only on these threads. Elsewhere (the inflation thread, the main
 * thread) they come from what these threads made, or are empty.
 */
public final class ImageThreads {
    private static final String TAG = "VmDecode";

    // Heads-up rows and the status bar wait for these threads, so they are not background threads: the urgent lane
    // runs at the default priority, the normal lane a little above background.
    private static final Lane NORMAL = new Lane("VmImages-", 3,
            Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE * 3);
    private static final Lane URGENT = new Lane("VmImagesUrgent-", 2, Process.THREAD_PRIORITY_DEFAULT);

    private ImageThreads() {
    }

    /** A queued piece of work. */
    public static final class Task {
        private final Lane mLane;
        private final String mOwner;
        private final Runnable mRunnable;
        private final Runnable mOnDropped;

        private Task(Lane lane, String owner, Runnable runnable, Runnable onDropped) {
            mLane = lane;
            mOwner = owner;
            mRunnable = runnable;
            mOnDropped = onDropped;
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
            Process.setThreadPriority(mLane.mPriority);
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

    /**
     * Tasks waiting in a lane, all apps together. When it is full, the app with the most waiting gives up its newest
     * task for a new task of another app, and a new task of that app itself is refused.
     */
    public static final int MAX_QUEUED = 256;

    // Where onDropped runs: never on the thread that queued the task that took the place.
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    /**
     * Runs {@code task} on a normal image thread, after the earlier tasks of {@code owner}. Null when the task is
     * refused (the lane is full and {@code owner} has the most waiting): the caller goes on without the image thread.
     * A task queued this way never gives way to another app's (for tests): callers pass {@code onDropped}.
     */
    static Task execute(String owner, Runnable task) {
        return NORMAL.add(owner, task, null);
    }

    /**
     * {@link #execute}, and when the task is dropped later for a task of an app with fewer waiting,
     * {@code onDropped} runs on the main thread instead of the task, so the caller can go on without it.
     */
    public static Task execute(String owner, Runnable task, Runnable onDropped) {
        return NORMAL.add(owner, task, onDropped);
    }

    /** Like {@link #execute}, in the urgent lane, which normal image work never holds up (for tests). */
    static Task executeUrgent(String owner, Runnable task) {
        return URGENT.add(owner, task, null);
    }

    /** Like {@link #execute(String, Runnable, Runnable)}, in the urgent lane. */
    public static Task executeUrgent(String owner, Runnable task, Runnable onDropped) {
        return URGENT.add(owner, task, onDropped);
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

        final int mPriority;

        Lane(String name, int threads, int priority) {
            mName = name;
            mThreads = threads;
            mPriority = priority;
        }

        Task add(String owner, Runnable runnable, Runnable onDropped) {
            final Task task = new Task(this, owner == null ? "" : owner, runnable, onDropped);
            int start = -1;
            Task dropped = null;
            synchronized (this) {
                if (mQueued >= MAX_QUEUED) {
                    // Full: the app with the most waiting gives up its newest task, unless that is this app (or it
                    // would have as many as that one afterwards).
                    ArrayDeque<Task> longest = null;
                    for (ArrayDeque<Task> q : mQueues.values()) {
                        if (longest == null || q.size() > longest.size()) {
                            longest = q;
                        }
                    }
                    final ArrayDeque<Task> own = mQueues.get(task.mOwner);
                    final int ownSize = own == null ? 0 : own.size();
                    if (longest == null || longest.size() <= ownSize + 1) {
                        Log.w(TAG, "image tasks full, refused for " + task.mOwner);
                        return null;
                    }
                    // Only a task whose caller is told can give way (the newest such one): a task nobody would hear
                    // of is never lost.
                    for (Iterator<Task> it = longest.descendingIterator(); it.hasNext(); ) {
                        final Task t = it.next();
                        if (t.mOnDropped != null) {
                            it.remove();
                            dropped = t;
                            break;
                        }
                    }
                    if (dropped == null) {
                        Log.w(TAG, "image tasks full, refused for " + task.mOwner);
                        return null;
                    }
                    mQueued--;
                    Log.w(TAG, "image tasks full, a task of " + dropped.mOwner + " gives way to "
                            + task.mOwner);
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
            if (dropped != null && dropped.mOnDropped != null) {
                final Runnable droppedCallback = dropped.mOnDropped;
                sMain.post(() -> Waiters.runSafely(droppedCallback));
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
