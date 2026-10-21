/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.qs;

import android.app.AlertDialog;
import android.app.Dialog;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.systemui.res.R;

/** Tally's fit of the organization dialog of {@link QSSecurityFooterUtils}. */
final class QSSecurityFooterTallyDialog {

    /**
     * Tally: fits the content, written for a platform dialog with no padding of its own, into the
     * SystemUI dialog, which pads its content and keys itself. The content's own side and top
     * padding go (it was inset twice, about 48 dp a side), and so does the last section's bottom
     * padding (the space above the keys is the key bar's own). Its title takes the dialog's title
     * slot, as in the other Quick Settings dialogs: the device management title, the parental
     * controls title and icon, or the subtitle of a single section, which stock hides in the
     * content.
     */
    @VisibleForTesting
    static void fitTallyDialog(Dialog dialog, View dialogView) {
        if (!(dialog instanceof AlertDialog alertDialog)
                || !(dialogView instanceof ViewGroup scroll)
                || scroll.getChildCount() == 0
                || !(scroll.getChildAt(0) instanceof ViewGroup sections)) {
            return;
        }
        sections.setPaddingRelative(0, 0, 0, 0);
        View lastSection = null;
        int shownSections = 0;
        for (int i = 0; i < sections.getChildCount(); i++) {
            View section = sections.getChildAt(i);
            if (section.getVisibility() == View.VISIBLE && section instanceof ViewGroup) {
                lastSection = section;
                shownSections++;
            }
        }
        if (lastSection != null) {
            lastSection.setPaddingRelative(lastSection.getPaddingStart(),
                    lastSection.getPaddingTop(), lastSection.getPaddingEnd(), 0);
        }

        TextView title = null;
        View managementSection = dialogView.findViewById(R.id.device_management_disclosures);
        TextView parentalTitle = dialogView.findViewById(R.id.parental_controls_title);
        if (parentalTitle != null) {
            title = parentalTitle;
            ImageView icon = dialogView.findViewById(R.id.parental_controls_icon);
            if (icon != null && icon.getDrawable() != null) {
                alertDialog.setIcon(icon.getDrawable());
                icon.setVisibility(View.GONE);
            }
        } else if (managementSection != null && managementSection.getVisibility() == View.VISIBLE) {
            title = dialogView.findViewById(R.id.device_management_subtitle);
        } else if (shownSections == 1 && lastSection != null) {
            title = sectionSubtitle(lastSection);
        }
        if (title != null && !TextUtils.isEmpty(title.getText())) {
            alertDialog.setTitle(title.getText());
            title.setVisibility(View.GONE);
        }
    }

    /** The subtitle of one of the organization dialog's sections. */
    @Nullable
    private static TextView sectionSubtitle(View section) {
        int id = section.getId();
        if (id == R.id.ca_certs_disclosures) {
            return section.findViewById(R.id.ca_certs_subtitle);
        } else if (id == R.id.network_logging_disclosures) {
            return section.findViewById(R.id.network_logging_subtitle);
        } else if (id == R.id.vpn_disclosures) {
            return section.findViewById(R.id.vpn_subtitle);
        }
        return null;
    }
}
