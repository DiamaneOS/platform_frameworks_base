// Copyright (C) 2026 DiamaneOS
package com.android.internal.diamaneos.cleancopies;

import java.util.function.IntFunction;

/** The archive state and the user selected with it travel as one value. @hide */
public final class ArchiveSource<T> {
    public final int user;
    public final T state;

    private ArchiveSource(int user, T state) {
        this.user = user;
        this.state = state;
    }

    public static <T> ArchiveSource<T> select(int preferred, int[] candidates, IntFunction<T> archived) {
        T state = archived.apply(preferred);
        if (state != null) return new ArchiveSource<>(preferred, state);
        for (int user : candidates) {
            state = archived.apply(user);
            if (state != null) return new ArchiveSource<>(user, state);
        }
        return null;
    }
}
