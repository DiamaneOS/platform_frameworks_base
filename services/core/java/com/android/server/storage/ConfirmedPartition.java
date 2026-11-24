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

package com.android.server.storage;

import android.os.storage.DiskInfo;
import android.os.storage.VolumeInfo;
import android.os.storage.VolumeActions;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** DiamaneOS: format and conversion have no authority to forget adopted storage. */
public final class ConfirmedPartition {
    public record View(DiskInfo disk, List<VolumeInfo> volumes) {}
    public interface Work { void run() throws Exception; }
    private ConfirmedPartition() {}

    private static void sameMedium(View view, String generation, long bytes) {
        DiskInfo disk = view.disk();
        if (disk == null || !Objects.equals(generation, disk.mediumGeneration)
                || bytes != disk.mediumBytes) {
            throw new IllegalStateException("The selected medium changed");
        }
    }

    public static void runForReset(DiskInfo requested, View current, Work partition) {
        sameMedium(current, requested.mediumGeneration, requested.mediumBytes);
        if (!current.disk().isAdoptable() || requested.mediumBytes <= 0
                || requested.mediumBytes != current.disk().size
                || requested.mediumGeneration == null || requested.mediumGeneration.isEmpty()) {
            throw new IllegalStateException("The reset target is not a verified adoptable disk");
        }
        try {
            partition.run();
        } catch (Exception e) {
            throw new IllegalStateException("The reset erase was not verified", e);
        }
    }

    public static void run(String diskId, String generation, long bytes, String forgetUuid,
            Supplier<View> snapshot, Work partitionAndWait, Consumer<String> forget) {
        run(diskId, generation, bytes, forgetUuid, snapshot, partitionAndWait, forget, false);
    }

    public static void run(String diskId, String generation, long bytes, String forgetUuid,
            Supplier<View> snapshot, Work partitionAndWait, Consumer<String> forget,
            boolean freshConfirmation) {
        View before = snapshot.get();
        sameMedium(before, generation, bytes);
        if (!VolumeActions.canFormatDisk(before.disk(), before.volumes(), freshConfirmation)) {
            throw new IllegalStateException("The selected storage state changed");
        }
        try {
            partitionAndWait.run();
        } catch (Exception e) {
            throw new IllegalStateException("The conversion was not verified", e);
        }
        View after = snapshot.get();
        sameMedium(after, generation, bytes);
        for (VolumeInfo vol : after.volumes()) {
            if (diskId.equals(vol.getDiskId()) && vol.getState() == VolumeInfo.STATE_FORMATTED_UNVERIFIED) {
                throw new android.os.ServiceSpecificException(4001, "Formatted, awaiting verification");
            }
            if (diskId.equals(vol.getDiskId()) && vol.type == VolumeInfo.TYPE_PRIVATE) {
                throw new IllegalStateException("The adopted volume is still present");
            }
        }
        // A conversion never retires an adopted key or record. Only the user's explicit Forget may do that.
    }
}
