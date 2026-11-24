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

package de.diamaneos.decode.client;

/** Compatible input audiences for reusable APK copies, apart from every private-input run. */
public final class CopyIsolation {
    private CopyIsolation() {
    }

    public static String identity(String packageName, String apk, boolean staged, long serial) {
        if (packageName == null || packageName.isEmpty() || serial < 0 || (staged && apk == null)) {
            return null;
        }
        return (staged ? "staged:" + apk + ":installer:" : "installed:") + packageName + ":serial" + serial;
    }
}
