// Copyright (C) 2026 DiamaneOS
package com.android.server.pm;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.DecodeGate;
import android.os.Environment;

import java.nio.file.Path;

/** Per-user archive files retain their source user on icon and export reads. */
final class ArchivedIconReads {
    private ArchivedIconReads() {}

    static Bitmap decode(Path path) {
        if (path == null) return null;
        Path file = path.toAbsolutePath().normalize();
        Path root = Environment.getDataSystemCeDirectory(0).toPath().getParent();
        if (!file.startsWith(root) || file.getNameCount() <= root.getNameCount() + 2) return null;
        Path relative = root.relativize(file);
        final int user;
        try {
            user = Integer.parseInt(relative.getName(0).toString());
        } catch (NumberFormatException e) {
            return null;
        }
        if (user < 0 || user >= 100_000 || !relative.getName(1).toString().equals("package_archiver")) return null;
        if (!file.startsWith(Environment.getDataSystemCeDirectory(user).toPath())) return null;
        try (DecodeGate.OwnerScope scope = DecodeGate.ownerScope(user)) {
            return BitmapFactory.decodeFile(file.toString());
        }
    }
}
