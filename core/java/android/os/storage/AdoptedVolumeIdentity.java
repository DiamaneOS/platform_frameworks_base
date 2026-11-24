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

    public static boolean partitionKeyInUse(String partGuid, Collection<VolumeRecord> remaining) {
        // Match vold's NormalizeHex separators and case folding for hexadecimal GUIDs.
        String key = partGuid.replaceAll("[ :\\-]", "");
        return !key.isEmpty() && remaining.stream().anyMatch(record -> record.partGuid != null
                && key.equalsIgnoreCase(record.partGuid.replaceAll("[ :\\-]", "")));
    }
}
