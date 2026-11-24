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

package android.util;

/** Host tests only: the log lines of the plain Java classes go to standard error. */
public final class Log {
    private Log() {
    }

    public static int e(String tag, String msg, Throwable tr) {
        System.err.println("E " + tag + ": " + msg + " " + tr);
        return 0;
    }

    public static int w(String tag, String msg) {
        System.err.println("W " + tag + ": " + msg);
        return 0;
    }
}
