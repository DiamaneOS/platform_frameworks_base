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

import android.content.Context;
import android.content.ContextParams;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.ImageFileGate;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;
import android.view.Display;

/**
 * An app's context whose resources load image files through an {@link ImageFileGate}: the context SystemUI applies
 * that app's notification layouts with. Its Resources object is made for it alone, so SystemUI's own resources and
 * every other Resources object load exactly as before. Contexts made from it ({@code RemoteViews} makes one for a
 * layout of another package or user) get the same gate.
 */
final class VmImageContext extends ContextWrapper {
    private final ImageFileGate mGate;

    private VmImageContext(Context base, ImageFileGate gate) {
        super(base);
        mGate = gate;
    }

    /** {@code context} with resources of its own behind {@code gate}. Throws when that cannot be made. */
    static Context wrap(Context context, ImageFileGate gate) {
        // A configuration context always gets a new Resources object from ResourcesManager
        // (ResourcesManager.createResourcesLocked). If that ever changed, the check below would refuse a shared one
        // only when it is the source context's: a gate set on a Resources object shared with other code would make
        // that code load app image files through the VM too, never natively, so it fails closed either way.
        final Context own = context.createConfigurationContext(context.getResources().getConfiguration());
        return gated(context, own, gate);
    }

    private static Context gated(Context from, Context own, ImageFileGate gate) {
        final Resources res = own.getResources();
        if (res == null || res == from.getResources() || res.getImageFileGate() != null) {
            throw new IllegalStateException("no resources of its own for the image gate");
        }
        res.setImageFileGate(gate);
        return new VmImageContext(own, gate);
    }

    @Override
    public Context createConfigurationContext(Configuration overrideConfiguration) {
        return gated(this, getBaseContext().createConfigurationContext(overrideConfiguration), mGate);
    }

    @Override
    public Context createApplicationContext(ApplicationInfo application, int flags)
            throws PackageManager.NameNotFoundException {
        return wrap(super.createApplicationContext(application, flags), mGate);
    }

    @Override
    public Context createPackageContext(String packageName, int flags)
            throws PackageManager.NameNotFoundException {
        return wrap(super.createPackageContext(packageName, flags), mGate);
    }

    @Override
    public Context createPackageContextAsUser(String packageName, int flags, UserHandle user)
            throws PackageManager.NameNotFoundException {
        return wrap(super.createPackageContextAsUser(packageName, flags, user), mGate);
    }

    @Override
    public Context createContextAsUser(UserHandle user, int flags) {
        return wrap(super.createContextAsUser(user, flags), mGate);
    }

    @Override
    public Context createContextForSplit(String splitName) throws PackageManager.NameNotFoundException {
        return wrap(super.createContextForSplit(splitName), mGate);
    }

    @Override
    public Context createDisplayContext(Display display) {
        return wrap(super.createDisplayContext(display), mGate);
    }

    @Override
    public Context createDeviceContext(int deviceId) {
        return wrap(super.createDeviceContext(deviceId), mGate);
    }

    @Override
    public Context createContext(ContextParams contextParams) {
        return wrap(super.createContext(contextParams), mGate);
    }

    @Override
    public Context createAttributionContext(String attributionTag) {
        return wrap(super.createAttributionContext(attributionTag), mGate);
    }

    @Override
    public Context createDeviceProtectedStorageContext() {
        return wrap(super.createDeviceProtectedStorageContext(), mGate);
    }

    @Override
    public Context createCredentialProtectedStorageContext() {
        return wrap(super.createCredentialProtectedStorageContext(), mGate);
    }

    @Override
    public Context createWindowContext(int type, Bundle options) {
        return wrap(super.createWindowContext(type, options), mGate);
    }

    @Override
    public Context createWindowContext(Display display, int type, Bundle options) {
        return wrap(super.createWindowContext(display, type, options), mGate);
    }

    @Override
    public Context createTokenContext(IBinder token, Display display) {
        return wrap(super.createTokenContext(token, display), mGate);
    }
}
