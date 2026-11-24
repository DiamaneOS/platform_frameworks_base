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

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.autofill.AutofillManager;
import android.widget.EditText;
import android.widget.LinearLayout;

/**
 * A form for the phone checks of the autofill images: its field asks for autofill at once. With the extra
 * {@code save} it fills the field in and finishes, so the service's save dialog shows.
 */
public class ProofFormActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final EditText field = new EditText(this);
        field.setHint("user name");
        field.setAutofillHints(View.AUTOFILL_HINT_USERNAME);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
        final LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 400, 48, 48);
        layout.addView(field, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        setContentView(layout);
        field.requestFocus();
        field.postDelayed(() -> getSystemService(AutofillManager.class).requestAutofill(field), 500);
        if (getIntent().getBooleanExtra("save", false)) {
            field.postDelayed(() -> {
                field.setText("saved-user");
                finish();
            }, 3_000);
        }
    }
}
