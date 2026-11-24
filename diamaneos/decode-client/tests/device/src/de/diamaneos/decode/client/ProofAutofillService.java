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

import android.app.assist.AssistStructure;
import android.graphics.drawable.Icon;
import android.os.CancellationSignal;
import android.os.Process;
import android.service.autofill.AutofillService;
import android.service.autofill.CustomDescription;
import android.service.autofill.Dataset;
import android.service.autofill.FillCallback;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveInfo;
import android.service.autofill.SaveRequest;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.widget.EditText;
import android.widget.RemoteViews;

import de.diamaneos.decode.client.tests.R;

/**
 * An autofill service for the phone checks: its dataset row and its save dialog show the image of
 * {@link UserImageProvider} by an explicitly qualified URI of the service user, so the colour and URI filter both identify that user.
 */
public class ProofAutofillService extends AutofillService {
    @Override
    public void onFillRequest(FillRequest request, CancellationSignal cancellationSignal, FillCallback callback) {
        final AssistStructure structure =
                request.getFillContexts().get(request.getFillContexts().size() - 1).getStructure();
        final AutofillId field = firstEditText(structure);
        if (field == null) {
            callback.onSuccess(null);
            return;
        }
        final Dataset dataset = new Dataset.Builder(row("dataset of user " + Process.myUserHandle().getIdentifier()))
                .setValue(field, AutofillValue.forText("proof-user"))
                .build();
        final SaveInfo save = new SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_USERNAME, new AutofillId[] {field})
                .setCustomDescription(new CustomDescription.Builder(row("save for user "
                        + Process.myUserHandle().getIdentifier())).build())
                .build();
        callback.onSuccess(new FillResponse.Builder().addDataset(dataset).setSaveInfo(save).build());
    }

    @Override
    public void onSaveRequest(SaveRequest request, SaveCallback callback) {
        callback.onSuccess();
    }

    private RemoteViews row(String text) {
        final RemoteViews views = new RemoteViews(getPackageName(), R.layout.vm_test_proof_row);
        views.setImageViewIcon(R.id.vm_proof_image, Icon.createWithContentUri(android.content.ContentProvider.maybeAddUserId(UserImageProvider.IMAGE,
                Process.myUserHandle().getIdentifier())));
        views.setTextViewText(R.id.vm_proof_text, text);
        return views;
    }

    private static AutofillId firstEditText(AssistStructure structure) {
        for (int i = 0; i < structure.getWindowNodeCount(); i++) {
            final AutofillId id = firstEditText(structure.getWindowNodeAt(i).getRootViewNode());
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    private static AutofillId firstEditText(AssistStructure.ViewNode node) {
        if (EditText.class.getName().equals(node.getClassName())) {
            return node.getAutofillId();
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            final AutofillId id = firstEditText(node.getChildAt(i));
            if (id != null) {
                return id;
            }
        }
        return null;
    }
}
