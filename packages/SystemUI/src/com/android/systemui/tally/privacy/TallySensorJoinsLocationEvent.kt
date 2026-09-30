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

package com.android.systemui.tally.privacy

import com.android.systemui.statusbar.events.PrivacyEvent

/**
 * Tally: the privacy event for a change that adds the camera or microphone to location alone.
 *
 * Stock only updates the privacy dot for a privacy change while the dot shows, and its dot turns
 * from location's colour to the sensors' one. Tally's dot has one colour for every sensor, so the
 * scheduler runs this event's chip even over the dot, as if no dot were showing
 * (`SystemStatusAnimationSchedulerImpl`). It is otherwise a stock [PrivacyEvent], with every
 * current privacy item; its name tells it apart in the scheduler's log.
 */
class TallySensorJoinsLocationEvent(showAnimation: Boolean) : PrivacyEvent(showAnimation)
