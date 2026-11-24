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

package com.android.systemui.statusbar.notification.row;

import android.net.Uri;
import de.diamaneos.decode.client.CapacityDemand;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;


/**
 * DiamaneOS: how the inline image loads of one row ended. An image the VM refused stays left out. A load that ended
 * without its image for a reason that passes (the decode service did not answer, no image thread was free) is no
 * cache entry. Missing URIs retain their own retry demand until made, refused or no longer wanted. The row bounds
 * recovery attempts per service wake independently of its main image work.
 */
final class InlineImageOutcomes {
    private final Set<Uri> mRefused = ConcurrentHashMap.newKeySet();
    private final CapacityDemand<Uri> mMissing = new CapacityDemand<>();
    private volatile Runnable mRetry;
    private volatile Runnable mDone;

    /** What inflates the row again once the decode service answers. */
    void setRetry(Runnable retry) {
        setRetry(retry, null);
    }

    void setRetry(Runnable retry, Runnable done) {
        mRetry = retry;
        mDone = done;
        if (!mMissing.isEmpty() && retry != null) retry.run();
    }

    /** The VM refused the image at {@code uri}: not loaded again for this row. */
    void refused(Uri uri) {
        mRefused.add(uri);
        mMissing.remove(uri);
    }

    boolean wasRefused(Uri uri) {
        return mRefused.contains(uri);
    }

    /** Only the URIs the row still shows are remembered. */
    void retain(Set<Uri> wanted) {
        mRefused.retainAll(wanted);
        mMissing.retain(wanted);
        demandChanged();
    }

    /** On the main thread: the load of {@code uri} ended, with its image or without. */
    void ended(Uri uri, boolean made, boolean stillWanted) {
        ended(uri, made, stillWanted, null, 0);
    }

    // DiamaneOS: retain the worker's lane evidence across the main-thread handoff.
    void ended(Uri uri, boolean made, boolean stillWanted, IntSupplier capacity, int observed) {
        if (made || !stillWanted || wasRefused(uri)) {
            mMissing.remove(uri);
        } else {
            mMissing.missing(uri, capacity, observed);
        }
        demandChanged();
    }

    IntSupplier capacityWakes() {
        return mMissing.capacityWakes();
    }

    int capacityObserved() {
        return mMissing.observed();
    }

    private void demandChanged() {
        if (mMissing.isEmpty()) {
            if (mDone != null) mDone.run();
        } else if (mRetry != null) {
            mRetry.run();
        }
    }
}
