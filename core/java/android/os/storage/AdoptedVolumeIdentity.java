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

package android.os.storage;
import android.text.TextUtils;
import java.util.Collection;
import java.util.Objects;
/** @hide DiamaneOS: a filesystem UUID never selects a partition key by itself. */
public final class AdoptedVolumeIdentity {
    private AdoptedVolumeIdentity() {}
    public static boolean matches(VolumeRecord record, VolumeInfo volume) {
        return record.type == VolumeInfo.TYPE_PRIVATE && volume.type == VolumeInfo.TYPE_PRIVATE
                && !TextUtils.isEmpty(record.partGuid) && record.partGuid.equalsIgnoreCase(volume.partGuid)
                && !TextUtils.isEmpty(record.diskSysPath) && volume.disk != null
                && Objects.equals(record.diskSysPath, volume.disk.sysPath);
    }
    public static void bindExisting(VolumeRecord record, int type, String partGuid, DiskInfo disk) {
        if (record.type != VolumeInfo.TYPE_PRIVATE || type != VolumeInfo.TYPE_PRIVATE
                || TextUtils.isEmpty(record.partGuid) || !record.partGuid.equalsIgnoreCase(partGuid)) return;
        if (TextUtils.isEmpty(record.diskSysPath) && disk != null) record.diskSysPath = disk.sysPath;
    }
    public static void checkForget(VolumeRecord record, java.util.List<VolumeInfo> volumes) {
        for (VolumeInfo volume : volumes) {
            if (volume.type == VolumeInfo.TYPE_PRIVATE && Objects.equals(record.fsUuid, volume.fsUuid)
                    && !matches(record, volume)) {
                throw new IllegalStateException("The saved partition identity is ambiguous");
            }
        }
    }

    /**
     * Forget all: every record goes, and these are the records whose native keys go with them. Refused, before
     * anything changes, when a record's identity is ambiguous. The key of a partition whose filesystem is present now
     * stays, as an explicit Forget keeps a key that a remaining record still names.
     */
    public static java.util.List<VolumeRecord> keysToForgetForAll(Collection<VolumeRecord> records,
            java.util.List<VolumeInfo> volumes) {
        for (VolumeRecord record : records) checkForget(record, volumes);
        final java.util.List<VolumeRecord> forget = new java.util.ArrayList<>();
        for (VolumeRecord record : records) {
            if (TextUtils.isEmpty(record.partGuid)) continue;
            boolean present = false;
            for (VolumeInfo volume : volumes) {
                present |= volume.type == VolumeInfo.TYPE_PRIVATE && volume.partGuid != null
                        && sameKey(record.partGuid, volume.partGuid);
            }
            if (!present) forget.add(record);
        }
        return forget;
    }

    // Match vold's NormalizeHex separators and case folding for hexadecimal GUIDs.
    private static boolean sameKey(String a, String b) {
        final String key = a.replaceAll("[ :\\-]", "");
        return !key.isEmpty() && key.equalsIgnoreCase(b.replaceAll("[ :\\-]", ""));
    }

    public static boolean partitionKeyInUse(String partGuid, Collection<VolumeRecord> remaining) {
        // Match vold's NormalizeHex separators and case folding for hexadecimal GUIDs.
        String key = partGuid.replaceAll("[ :\\-]", "");
        return !key.isEmpty() && remaining.stream().anyMatch(record -> record.partGuid != null
                && key.equalsIgnoreCase(record.partGuid.replaceAll("[ :\\-]", "")));
    }
}
