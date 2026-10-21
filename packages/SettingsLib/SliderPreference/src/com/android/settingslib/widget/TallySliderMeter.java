/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;

import androidx.annotation.NonNull;

import com.android.settingslib.widget.preference.slider.R;

import com.google.android.material.slider.Slider;

/** Tally's drawing of {@link SliderPreference}'s slider. */
final class TallySliderMeter {

    /**
     * Tally: the slider as the prototype's meters (app.css .cl-meter, .vol-meter), in place of
     * Material's pill track, stop dots and handle bar: an r8 track on the high surface, the level
     * in ink at 16 % over it, and the value as a lamp tick across the track. A level is a value,
     * neither a state nor an action, so it takes no accent colour. The unfilled part is the same
     * high surface as SystemUI's Tally brightness slider's (tally_surface_high). The colours are
     * the Tally roles SettingsTheme exports from API 34 (res/values-v34/tally_switch.xml).
     */
    static void applyTallyMeter(@NonNull Slider slider) {
        final Context context = slider.getContext();
        final Resources res = context.getResources();
        final int height = res.getDimensionPixelSize(R.dimen.settingslib_tally_meter_height);
        slider.setTrackHeight(height);
        slider.setTrackCornerSize(
                res.getDimensionPixelSize(R.dimen.settingslib_tally_meter_radius));
        slider.setTrackInsideCornerSize(0);
        slider.setThumbTrackGapSize(0);
        slider.setTrackStopIndicatorSize(0);
        slider.setThumbWidth(res.getDimensionPixelSize(R.dimen.settingslib_tally_meter_tick_width));
        slider.setThumbHeight(height);
        slider.setThumbElevation(0);
        slider.setThumbStrokeWidth(res.getDimension(R.dimen.settingslib_tally_meter_tick_edge));

        final int surface = context.getColor(
                com.android.settingslib.widget.theme.R.color.settingslib_tally_surface_high);
        final int ink = context.getColor(
                com.android.settingslib.widget.theme.R.color.settingslib_tally_ink);
        final int lamp = context.getColor(
                com.android.settingslib.widget.theme.R.color.settingslib_tally_lamp);
        final int lampOutline = context.getColor(
                com.android.settingslib.widget.theme.R.color.settingslib_tally_lamp_outline);
        slider.setTrackActiveTintList(tallyTint(over(withAlpha(ink, 0.16f), surface)));
        slider.setTrackInactiveTintList(tallyTint(surface));
        slider.setThumbTintList(tallyTint(lamp));
        slider.setThumbStrokeColor(tallyTint(lampOutline));
        // The scale marks of a stepped slider, as the volume meter's (ink at 18 %).
        final ColorStateList mark = tallyTint(withAlpha(ink, 0.18f));
        slider.setTickActiveTintList(mark);
        slider.setTickInactiveTintList(mark);
    }

    /** {@code color}, and at 38 % of its alpha while the slider is disabled, as Material does. */
    private static ColorStateList tallyTint(int color) {
        return new ColorStateList(
                new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                new int[] {withAlpha(color, 0.38f * Color.alpha(color) / 255f), color});
    }

    private static int withAlpha(int color, float alpha) {
        return (Math.round(alpha * 255f) << 24) | (color & 0x00ffffff);
    }

    /** {@code top} drawn over the opaque {@code bottom}. */
    private static int over(int top, int bottom) {
        final float a = Color.alpha(top) / 255f;
        return Color.rgb(
                Math.round(Color.red(top) * a + Color.red(bottom) * (1f - a)),
                Math.round(Color.green(top) * a + Color.green(bottom) * (1f - a)),
                Math.round(Color.blue(top) * a + Color.blue(bottom) * (1f - a)));
    }
}
