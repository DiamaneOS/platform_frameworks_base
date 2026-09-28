/*
 * Copyright (C) 2026 The DiamaneOS Project
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

package com.android.systemui.shared.recents;

/**
 * Listener that Launcher sets with {@link ISystemUiProxy#setStoppableAppsListener} to learn which
 * apps Recents may offer to stop ("Still running · Stop" in the Tally shell).
 */
interface IStoppableAppsListener {
    /**
     * The apps that SystemUI's Active apps dialog lists with a Stop button because they run a
     * foreground service, for the current user and its profiles: app i is packageNames[i] in user
     * userIds[i]. Always the whole set, empty when there is none.
     */
    oneway void onStoppableAppsChanged(in String[] packageNames, in int[] userIds);
}
