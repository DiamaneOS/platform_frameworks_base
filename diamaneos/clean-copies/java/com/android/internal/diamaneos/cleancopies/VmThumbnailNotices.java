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

package com.android.internal.diamaneos.cleancopies;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.provider.DocumentsContract;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.function.Predicate;

/**
 * Thumbnails ({@link VmThumbnails}) that failed because the decode service did not answer: once it answers again
 * ({@link CleanCopyGate}, the system server's decode-answers notice), each of their URIs gets one change notice, so a
 * caller that observes the item loads it again. A document's provider also gets one notice on its roots URI, which is
 * what Files (DocumentsUI) observes: it registers on content://AUTHORITY/root, not on listings or items. No grant and
 * nothing stored: in memory only, at most {@link #MAX_URIS} URIs, each once, all of them dropped when the notices are
 * sent, so a long outage never ends in more than twice {@link #MAX_URIS} notices.
 *
 * @hide
 */
public final class VmThumbnailNotices {
    private static final String TAG = "VmThumbnailNotices";

    /** URIs remembered at most during one outage. Later failures are not remembered. */
    public static final int MAX_URIS = 64;

    private static final Object sLock = new Object();
    private static LinkedHashSet<Uri> sFailed = new LinkedHashSet<>(); // guarded by sLock

    private VmThumbnailNotices() {
    }

    /** The thumbnail of {@code uri} failed for an outage of the decode service. */
    static void failed(Uri uri, int mark) {
        synchronized (sLock) {
            if (sFailed.size() < MAX_URIS) sFailed.add(uri);
        }
        // The notice that the service answers again comes to processes that watch: this one watches from now on.
        if (CleanCopyGate.decodeAnswersMark() != mark) answers(null, null);
    }

    /** The thumbnail of {@code uri} was made after all: no notice for it. */
    static void loaded(Uri uri) {
        synchronized (sLock) {
            sFailed.remove(uri);
        }
    }

    /**
     * The decode service answers again: the remembered URIs are taken (none are left) and each is notified once on
     * {@code handler}'s thread.
     */
    static void answers(Context context, Handler handler) {
        final ArrayList<Uri> due;
        synchronized (sLock) {
            if (sFailed.isEmpty()) return;
            due = new ArrayList<>(sFailed);
            sFailed = new LinkedHashSet<>();
        }
        final Context app;
        try {
            app = context != null ? context : VmThumbnails.context();
        } catch (java.io.IOException e) {
            return;
        }
        final Runnable send = () -> send(app.getContentResolver(),
                notices(due, uri -> matches(app, uri, DocumentsContract::isDocumentUri)));
        if (handler != null) {
            handler.post(send);
        } else {
            new Thread(send, "VmThumbnailNotices").start();
        }
    }

    /** The notices for {@code due}: each URI, then the roots URI of each documents provider among them, once. */
    static ArrayList<Uri> notices(ArrayList<Uri> due, Predicate<Uri> document) {
        final LinkedHashSet<Uri> all = new LinkedHashSet<>(due);
        for (Uri uri : due) {
            if (document.test(uri)) all.add(uri.buildUpon().path("/root").clearQuery().fragment(null).build());
        }
        return new ArrayList<>(all);
    }

    private static boolean matches(Context context, Uri uri, java.util.function.BiPredicate<Context, Uri> test) {
        try {
            final int user = android.content.ContentProvider.getUserIdFromUri(uri);
            return test.test(context.createContextAsUser(android.os.UserHandle.of(user), 0),
                    android.content.ContentProvider.getUriWithoutUserId(uri));
        } catch (RuntimeException e) { return false; }
    }

    /** A live directory observes recovery roots without observing its own file-change notices twice. */
    public static Runnable directoryRecovery(Context context, Uri directory, Runnable changed) {
        final ContentResolver resolver = context.getContentResolver();
        final android.os.CancellationSignal closed = new android.os.CancellationSignal();
        final android.database.ContentObserver observer = new android.database.ContentObserver(null) {
            @Override public void onChange(boolean self) { if (!closed.isCanceled()) changed.run(); }
        };
        closed.setOnCancelListener(() -> {
            try { resolver.unregisterContentObserver(observer); } catch (RuntimeException ignored) { }
        });
        try { resolver.registerContentObserver(DocumentsContract.buildRootsUri(directory.getAuthority()), false, observer); }
        catch (RuntimeException e) { closed.cancel(); }
        return closed::cancel;
    }

    static void send(ContentResolver resolver, ArrayList<Uri> due) {
        for (Uri uri : due) {
            try {
                // A background provider can hold the live cursor for a foreground Files window.
                final boolean roots = "/root".equals(uri.getEncodedPath())
                        && matches(resolver.getContext(), uri, DocumentsContract::isRootsUri);
                resolver.notifyChange(uri, null, roots ? ContentResolver.NOTIFY_NO_DELAY : 0);
            } catch (RuntimeException e) {
                // A provider this process may no longer reach (uninstalled, another user): no notice for it.
                Log.w(TAG, "no change notice for a thumbnail: " + e.getClass().getSimpleName());
            }
        }
    }

    /** Tests: the URIs remembered now. */
    public static ArrayList<Uri> pendingForTest() {
        synchronized (sLock) {
            return new ArrayList<>(sFailed);
        }
    }
}
