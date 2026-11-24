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

import android.annotation.NonNull;
import android.content.res.Resources;

/**
 * DiamaneOS: what the storage page and the notices offer for an SD card or USB drive (a public volume), decided once
 * per state for Settings and SystemUI, and whether new adoptable storage is offered at all.
 *
 * <p>A volume the filesystem VM could not open, one not written back and one that waits for a protected VM place can
 * be recovered: its page stays open and offers Retry. A readable one offers Browse and Eject. None of them, and no
 * read-only volume, is ever offered for formatting: a volume that may hold the user's data is erased only through
 * a deliberate format of a working card.
 *
 * @hide
 */
public final class VolumeActions {
    /** Mount it again. */
    public static final int RETRY = 1;
    /** Open its files: readable volumes only. */
    public static final int BROWSE = 1 << 1;
    /** Unmount it: mounted volumes only. */
    public static final int EJECT = 1 << 2;
    /** Erase it through the format wizard. */
    public static final int FORMAT = 1 << 3;

    private VolumeActions() {
    }

    /**
     * Whether new adoptable storage (a card set up as internal storage) is offered and accepted:
     * config_newAdoptableStorage. Cards adopted before keep working either way.
     */
    public static boolean newAdoptableStorage() {
        return Resources.getSystem().getBoolean(com.android.internal.R.bool.config_newAdoptableStorage);
    }

    /**
     * Whether a public volume in this state can be recovered: it could not be opened, was not written back, or waits
     * for a protected VM place.
     */
    public static boolean isRecoverable(int state) {
        return state == VolumeInfo.STATE_UNOPENABLE || state == VolumeInfo.STATE_NOT_WRITTEN_BACK
                || state == VolumeInfo.STATE_WAITING_FOR_PLACE;
    }

    /**
     * Whether the storage page of a public volume shows in this state: readable, recoverable, or checked (a Retry
     * goes through checking). Stock shows it for readable volumes only.
     */
    public static boolean showsPage(int state) {
        return state == VolumeInfo.STATE_MOUNTED || state == VolumeInfo.STATE_MOUNTED_READ_ONLY
                || state == VolumeInfo.STATE_CHECKING || isRecoverable(state);
    }

    /** The actions a public volume offers in its state: RETRY, BROWSE, EJECT and FORMAT. */
    public static int forPublic(@NonNull VolumeInfo vol) {
        switch (vol.getState()) {
            case VolumeInfo.STATE_MOUNTED:
                return BROWSE | EJECT | FORMAT;
            case VolumeInfo.STATE_MOUNTED_READ_ONLY:
                // Being checked, or in need of a check on a computer: its files stay reachable, never erased here.
                return BROWSE | EJECT;
            case VolumeInfo.STATE_UNOPENABLE:
            case VolumeInfo.STATE_NOT_WRITTEN_BACK:
                return RETRY;
            case VolumeInfo.STATE_UNMOUNTED:
                return RETRY | FORMAT;
            case VolumeInfo.STATE_UNMOUNTABLE:
                // Two filesystem VMs found no filesystem they can read: stock's format offer.
                return FORMAT;
            default:
                // Checking and waiting volumes mount by themselves. Ejecting, formatting and removed ones offer
                // nothing.
                return 0;
        }
    }

    /** Whether the public volume offers this action now. */
    public static boolean allows(@NonNull VolumeInfo vol, int action) {
        return (forPublic(vol) & action) != 0;
    }

    /**
     * The status text of a volume, a framework string: its state's, and "Checking…" for one that is read-only while
     * its filesystem VM checks it, which may make it writable again.
     */
    public static int statusText(@NonNull VolumeInfo vol) {
        if (vol.getState() == VolumeInfo.STATE_MOUNTED_READ_ONLY && vol.checkPending) {
            return com.android.internal.R.string.ext_media_status_checking;
        }
        return vol.getStateDescription();
    }
}
