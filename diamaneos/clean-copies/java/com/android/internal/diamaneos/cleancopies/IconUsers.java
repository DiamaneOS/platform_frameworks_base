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

import java.util.function.IntToLongFunction;

/** Immutable source and resolver user incarnations for icon cache, refusal and pending keys. */
public final class IconUsers {
    private final int mSource;
    private final int mResolver;
    private final long mSourceSerial;
    private final long mResolverSerial;

    public IconUsers(int source, int resolver, IntToLongFunction serial) {
        mSource = source;
        mResolver = resolver;
        mSourceSerial = serial.applyAsLong(source);
        mResolverSerial = serial.applyAsLong(resolver);
    }

    public String key() {
        return "u" + mSource + "s" + mSourceSerial + "/r" + mResolver + "s" + mResolverSerial;
    }

    public boolean current(IntToLongFunction serial) {
        return mSourceSerial >= 0 && mResolverSerial >= 0
                && mSourceSerial == serial.applyAsLong(mSource)
                && mResolverSerial == serial.applyAsLong(mResolver);
    }
}
