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


package com.android.server.wm;

import android.graphics.Bitmap;
import android.graphics.DecodeGate;

/**
 * DiamaneOS: a task's icon file, read back for whoever asks (SystemUI's recents, a launcher of
 * the parent user), is an image of the task's user: the user whose images folder holds it, which
 * ActivityTaskManagerService checked against the path. It is decoded in that user's scope
 * (DecodeGate.ownerScope), never as the asking process's user.
 */
final class TaskIcons {
    private TaskIcons() {
    }

    /** The icon at {@code filePath} of user {@code userId}'s task, or null without one. */
    static Bitmap restore(String filePath, int userId) {
        try (DecodeGate.OwnerScope owner = DecodeGate.ownerScope(userId)) {
            return TaskPersister.restoreImage(filePath);
        }
    }
}
