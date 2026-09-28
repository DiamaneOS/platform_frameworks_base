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

package com.android.systemui.tally.shade

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.systemui.qs.ui.compose.borderOnFocus
import org.diamaneos.tally.R as TallyR

/**
 * Tally's outlined keys, as the shade's footer keys (app.css `.fkey`): 48 dp tall with the key
 * radius, a hairline in the outline variant and no fill, with ink icons and Tally's label type.
 */
object TallyKeyDefaults {
    val height: Dp
        @Composable @ReadOnlyComposable get() = dimensionResource(TallyR.dimen.tally_key_height)

    val radius: Dp
        @Composable @ReadOnlyComposable get() = dimensionResource(TallyR.dimen.tally_radius_s)

    val contentColor: Color
        @Composable @ReadOnlyComposable get() = colorResource(TallyR.color.tally_ink)

    val focusColor: Color
        @Composable @ReadOnlyComposable get() = colorResource(TallyR.color.tally_accent)

    /** An icon in a key, 20 dp as the prototype's. */
    val iconSize: Dp = 20.dp

    /** Between an icon and its label, and a key's padding at its sides (app.css `.fkey`). */
    val iconGap: Dp = 6.dp
    val sidePadding: Dp = 10.dp

    @Composable
    @ReadOnlyComposable
    fun border(): BorderStroke =
        BorderStroke(
            dimensionResource(TallyR.dimen.tally_stroke_hairline),
            colorResource(TallyR.color.tally_outline_variant),
        )

    @Composable
    fun labelStyle(): TextStyle =
        tallyTextStyle(
            TallyR.style.TextAppearance_Tally_Label,
            TallyR.dimen.tally_type_label_line_height,
        )
}

/**
 * An outlined key with an icon and its label, for a key the shade draws itself (Edit).
 *
 * @param contentDescription what screen readers hear for the whole key, in place of the label.
 */
@Composable
fun TallyKey(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val shape = RoundedCornerShape(TallyKeyDefaults.radius)
    val ink = TallyKeyDefaults.contentColor
    Row(
        modifier =
            modifier
                .height(TallyKeyDefaults.height)
                .borderOnFocus(TallyKeyDefaults.focusColor, CornerSize(TallyKeyDefaults.radius))
                .clip(shape)
                .border(TallyKeyDefaults.border(), shape)
                .clickable(role = Role.Button, onClick = onClick)
                .then(
                    if (contentDescription != null) {
                        Modifier.clearAndSetSemantics {
                            this.contentDescription = contentDescription
                            role = Role.Button
                        }
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = TallyKeyDefaults.sidePadding),
        horizontalArrangement = Arrangement.spacedBy(TallyKeyDefaults.iconGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(TallyKeyDefaults.iconSize),
        )
        BasicText(label, style = TallyKeyDefaults.labelStyle(), color = { ink }, maxLines = 1)
    }
}
