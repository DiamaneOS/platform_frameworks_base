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

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.IntArray;
import android.util.Log;
import android.util.LongArray;
import android.util.Pair;
import android.util.SizeF;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.widget.RemoteViews;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds every image in an app's notification layout (RemoteViews) and, on SystemUI's own copy of it, turns each into
 * a bitmap made in the VM, in place: Icons of every action ({@code setImageViewIcon}, {@code setIcon} with day and
 * night icons, compound drawables), nested layouts ({@code addView} children, landscape and portrait, sized layouts,
 * collection items), and {@code setImageViewUri} actions, which become {@code setImageIcon} with the decoded bitmap.
 * Order and targets of the actions stay as the app made them.
 *
 * <p>RemoteViews has no API for this and its source is pinned by platform_patches, so its actions are read by
 * reflection, generically: every field of every RemoteViews class is searched, so an action type added upstream is
 * covered too. A Bundle given to a view ({@code setBundle}) is accepted only for the messaging and call layouts
 * ({@code setData}, {@code setGroupFacePile}), only up to a size, and only with the keys those views read, each read
 * with the class it must have, so no value of another class is ever made. It is replaced by a new Bundle of those
 * values, whose Person and Icon values are re-made like the others.
 *
 * <p>Deny by default: only known containers are opened and only known value types that hold no image are passed.
 * Anything else (an unknown object, a link outside {@code setImageURI}, draw instructions, a Bundle that cannot be
 * read, a reflection failure after an upstream change) makes the caller drop the layout, and the standard one shows.
 */
final class RemoteViewsImages {
    private static final String TAG = "VmDecode";
    private static final String REMOTE_VIEWS = "android.widget.RemoteViews";
    private static final String SET_IMAGE_URI = "setImageURI";
    private static final String SET_IMAGE_ICON = "setImageIcon";
    private static final int MAX_DEPTH = 64;
    private static final int MAX_OBJECTS = 20_000;

    /** What to do with the images found. */
    interface Images {
        /** True when {@code icon} must be re-made (not a bitmap, not a resource of the system image). */
        boolean needsWork(Icon icon);

        /** The bitmap icon to replace {@code icon} with: never null, an empty bitmap when it cannot be made. */
        Icon convert(Icon icon);

        /** The bitmap icon for the image behind {@code uri}: never null, an empty bitmap when it cannot be made. */
        Icon convert(Uri uri);
    }

    // RemoteViews.ReflectionAction and the fields of a setImageViewUri action.
    private static final Class<?> sReflectionAction;
    private static final Field sMethodName;
    private static final Field sType;
    private static final Field sValue;
    private static final int sUriType;
    private static final int sIconType;
    private static final int sBundleType;
    private static final int sIntType;
    // Every reflection action (RemoteViews.BaseReflectionAction) and its INTENT type: a list adapter can be set
    // through any of them by method name.
    private static final Class<?> sBaseReflectionAction;
    private static final int sIntentType;

    static {
        Class<?> action = null;
        Field methodName = null;
        Field type = null;
        Field value = null;
        int uriType = -1;
        int iconType = -1;
        int bundleType = -1;
        int intType = -1;
        Class<?> baseAction = null;
        int intentType = -1;
        try {
            action = Class.forName(REMOTE_VIEWS + "$ReflectionAction");
            final Class<?> base = action.getSuperclass();
            methodName = base.getDeclaredField("mMethodName");
            type = base.getDeclaredField("mType");
            value = action.getDeclaredField("mValue");
            methodName.setAccessible(true);
            type.setAccessible(true);
            value.setAccessible(true);
            final Field uri = base.getDeclaredField("URI");
            final Field icon = base.getDeclaredField("ICON");
            uri.setAccessible(true);
            icon.setAccessible(true);
            uriType = uri.getInt(null);
            iconType = icon.getInt(null);
            final Field bundle = base.getDeclaredField("BUNDLE");
            final Field integer = base.getDeclaredField("INT");
            bundle.setAccessible(true);
            integer.setAccessible(true);
            bundleType = bundle.getInt(null);
            intType = integer.getInt(null);
            final Field intent = base.getDeclaredField("INTENT");
            intent.setAccessible(true);
            intentType = intent.getInt(null);
            baseAction = base;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.e(TAG, "RemoteViews actions cannot be read: custom layouts with images are dropped", e);
            action = null;
        }
        sReflectionAction = action;
        sMethodName = methodName;
        sType = type;
        sValue = value;
        sUriType = uriType;
        sIconType = iconType;
        sBundleType = bundleType;
        sIntType = intType;
        sBaseReflectionAction = baseAction;
        sIntentType = intentType;
    }

    private static final Map<Class<?>, Field[]> sFields = new ConcurrentHashMap<>();

    // Fields that hold no image a view is given: the parcel's class cookies (opaque to RemoteViews, never applied)
    // and the inflater factory SystemUI itself sets on the views it applies.
    private static final Set<String> SKIPPED_FIELDS = Set.of("mClassCookies", "mLayoutInflaterFactory2");

    // The view methods a Bundle may be given to, and the keys they read with the kind of each value.
    private static final Set<String> BUNDLE_METHODS = Set.of("setData", "setGroupFacePile");
    private enum Kind { PERSON, ICON, MESSAGES, PEOPLE, TEXT, BOOLEAN, INT, LONG }
    private static final Map<String, Kind> DATA_KEYS = new HashMap<>();
    private static final Map<String, Kind> MESSAGE_KEYS = new HashMap<>();

    static {
        DATA_KEYS.put(Notification.EXTRA_MESSAGES, Kind.MESSAGES);
        DATA_KEYS.put(Notification.EXTRA_HISTORIC_MESSAGES, Kind.MESSAGES);
        DATA_KEYS.put(Notification.EXTRA_MESSAGING_PERSON, Kind.PERSON);
        DATA_KEYS.put(Notification.EXTRA_CALL_PERSON, Kind.PERSON);
        DATA_KEYS.put(Notification.EXTRA_PEOPLE_LIST, Kind.PEOPLE);
        DATA_KEYS.put(Notification.EXTRA_CONVERSATION_ICON, Kind.ICON);
        DATA_KEYS.put(Notification.EXTRA_VERIFICATION_ICON, Kind.ICON);
        DATA_KEYS.put(Notification.EXTRA_LARGE_ICON, Kind.ICON);
        DATA_KEYS.put(Notification.EXTRA_LARGE_ICON_BIG, Kind.ICON);
        DATA_KEYS.put(Notification.EXTRA_TITLE, Kind.TEXT);
        DATA_KEYS.put(Notification.EXTRA_TEXT, Kind.TEXT);
        DATA_KEYS.put(Notification.EXTRA_CONVERSATION_TITLE, Kind.TEXT);
        DATA_KEYS.put(Notification.EXTRA_SELF_DISPLAY_NAME, Kind.TEXT);
        DATA_KEYS.put(Notification.EXTRA_VERIFICATION_TEXT, Kind.TEXT);
        DATA_KEYS.put("android.hiddenConversationTitle", Kind.TEXT);
        DATA_KEYS.put(Notification.EXTRA_IS_GROUP_CONVERSATION, Kind.BOOLEAN);
        DATA_KEYS.put("android.remoteInputSpinner", Kind.BOOLEAN);
        DATA_KEYS.put(Notification.EXTRA_CALL_TYPE, Kind.INT);
        DATA_KEYS.put("android.conversationUnreadMessageCount", Kind.INT);
        // Notification.MessagingStyle.Message: the keys of one message. An image message ("type" and "uri") is not
        // among them: its layout is dropped.
        MESSAGE_KEYS.put("text", Kind.TEXT);
        MESSAGE_KEYS.put("time", Kind.LONG);
        MESSAGE_KEYS.put("sender", Kind.TEXT);
        MESSAGE_KEYS.put("sender_person", Kind.PERSON);
        MESSAGE_KEYS.put("remote_input_history", Kind.BOOLEAN);
    }

    // The most bytes a Bundle given to a view may hold, checked before it is read.
    private static final int MAX_BUNDLE_BYTES = 256 * 1024;
    private static final int MAX_MESSAGES = 64;
    private static final String MESSAGE_EXTRAS = "extras";

    // Bundles this class made from checked values: accepted as they are when the layout is checked again.
    private static final Set<Bundle> sFiltered = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    // Values that hold no image: plain values, pixels, colours, intents (sent on a click, never drawn) and remote
    // inputs (a label and choices, given to the reply field).
    private static final Class<?>[] LEAVES = {
            CharSequence.class, Number.class, Boolean.class, Character.class, Enum.class, Class.class,
            Bitmap.class, ColorStateList.class, PendingIntent.class, Intent.class, ApplicationInfo.class,
            RemoteInput.class,
            SizeF.class, SparseIntArray.class, SparseBooleanArray.class, IntArray.class, LongArray.class,
            Instant.class, Duration.class,
    };

    private final Images mImages;
    private final boolean mConvert;
    private List<Integer> mCollect; // when collecting resource IDs
    private List<Integer> mLayouts; // when collecting resource IDs
    private final IdentityHashMap<Object, Boolean> mSeen = new IdentityHashMap<>();
    private boolean mNeedsWork;
    private String mUnsafe;

    private RemoteViewsImages(Images images, boolean convert) {
        mImages = images;
        mConvert = convert;
    }

    /** True when {@code views} holds an image to re-make, or anything that would make it unsafe. Changes nothing. */
    static boolean needsWork(RemoteViews views, Images images) {
        if (views == null) {
            return false;
        }
        final RemoteViewsImages scan = new RemoteViewsImages(images, false);
        scan.run(views);
        return scan.mNeedsWork || scan.mUnsafe != null;
    }

    /**
     * Re-makes every image of {@code views} in place. Returns false when the layout must be dropped instead: it holds
     * something that cannot be converted, or a check afterwards still finds an image that was not.
     */
    /**
     * A copy of {@code views} that shares nothing with it, list items of the collection cache included (the clone of
     * RemoteViews keeps the cache's items), made through a parcel. What is converted in the copy never changes the
     * original.
     */
    static RemoteViews deepCopy(RemoteViews views) {
        final android.os.Parcel parcel = android.os.Parcel.obtain();
        try {
            views.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return RemoteViews.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    static boolean convert(RemoteViews views, Images images) {
        if (views == null) {
            return true;
        }
        final RemoteViewsImages walk = new RemoteViewsImages(images, true);
        walk.run(views);
        if (walk.mUnsafe != null) {
            Log.w(TAG, "custom notification layout dropped: " + walk.mUnsafe);
            return false;
        }
        // Checked again with fresh eyes: nothing left to re-make, and no link left (RemoteViews' own list).
        final RemoteViewsImages check = new RemoteViewsImages(images, false);
        check.run(views);
        final boolean[] link = {false};
        try {
            views.visitUris(uri -> link[0] = true);
        } catch (RuntimeException e) {
            link[0] = true;
        }
        if (check.mNeedsWork || check.mUnsafe != null || link[0]) {
            Log.w(TAG, "custom notification layout dropped: images left after converting");
            return false;
        }
        return true;
    }

    /** Every Icon in {@code views}, for tests. Empty when they cannot be read. */
    static List<Icon> icons(RemoteViews views) {
        final List<Icon> icons = new ArrayList<>();
        final RemoteViewsImages walk = new RemoteViewsImages(new Images() {
            @Override
            public boolean needsWork(Icon icon) {
                icons.add(icon);
                return false;
            }

            @Override
            public Icon convert(Icon icon) {
                return icon;
            }

            @Override
            public Icon convert(Uri uri) {
                return null;
            }
        }, false);
        walk.run(views);
        return icons;
    }

    private void run(RemoteViews views) {
        if (sReflectionAction == null) {
            mUnsafe = "RemoteViews actions cannot be read";
            return;
        }
        try {
            visit(views, 0);
        } catch (ReflectiveOperationException | RuntimeException e) {
            mUnsafe = "RemoteViews actions cannot be read: " + e;
        }
    }

    private void visit(Object o, int depth) throws ReflectiveOperationException {
        if (o == null || mUnsafe != null) {
            return;
        }
        if (o instanceof Icon) {
            visitIcon((Icon) o);
            return;
        }
        if (o instanceof Uri) {
            mUnsafe = "a link outside an image action";
            return;
        }
        if (isLeaf(o)) {
            return;
        }
        if (depth > MAX_DEPTH || mSeen.size() > MAX_OBJECTS) {
            mUnsafe = "layout too deep";
            return;
        }
        if (mSeen.put(o, Boolean.TRUE) != null) {
            return;
        }
        if (o instanceof Object[]) {
            for (Object element : (Object[]) o) {
                visit(element, depth + 1);
            }
        } else if (o instanceof Collection) {
            for (Object element : (Collection<?>) o) {
                visit(element, depth + 1);
            }
        } else if (o instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) o).entrySet()) {
                visit(entry.getKey(), depth + 1);
                visit(entry.getValue(), depth + 1);
            }
        } else if (o instanceof SparseArray) {
            final SparseArray<?> array = (SparseArray<?>) o;
            for (int i = 0; i < array.size(); i++) {
                visit(array.valueAt(i), depth + 1);
            }
        } else if (o instanceof Pair) {
            visit(((Pair<?, ?>) o).first, depth + 1);
            visit(((Pair<?, ?>) o).second, depth + 1);
        } else if (o instanceof RemoteViews || o.getClass().getName().startsWith(REMOTE_VIEWS + "$")) {
            visitRemoteViewsObject(o, depth);
        } else {
            // Deny by default: an object of a type not known to hold no image.
            mUnsafe = "an unknown value " + o.getClass().getName();
        }
    }

    // A reflection action that sets a list adapter by method name, or that hands a view an Intent at all.
    private static boolean setsServiceAdapter(Object o) throws ReflectiveOperationException {
        if (sBaseReflectionAction == null || !sBaseReflectionAction.isInstance(o)) {
            return false;
        }
        final Object name = sMethodName.get(o);
        if (name instanceof String && ((String) name).toLowerCase(java.util.Locale.ROOT).contains("adapter")) {
            return true;
        }
        return sType.getInt(o) == sIntentType;
    }

    private static boolean isLeaf(Object o) {
        final Class<?> cls = o.getClass();
        if (cls.isArray() && cls.getComponentType().isPrimitive()) {
            return true;
        }
        for (Class<?> leaf : LEAVES) {
            if (leaf.isInstance(o)) {
                return true;
            }
        }
        return false;
    }

    private void visitRemoteViewsObject(Object o, int depth) throws ReflectiveOperationException {
        if (o instanceof RemoteViews && ((RemoteViews) o).getLayoutId() == 0) {
            // Draw instructions (remote compose) carry images the player decodes itself.
            mUnsafe = "draw instructions";
            return;
        }
        if (o instanceof RemoteViews && mLayouts != null) {
            mLayouts.add(((RemoteViews) o).getLayoutId());
        }
        if (o.getClass().getName().endsWith("$SetDrawInstructionAction")) {
            mUnsafe = "draw instructions";
            return;
        }
        if (o.getClass().getName().endsWith("$SetRemoteViewsAdapterIntent") || setsServiceAdapter(o)) {
            // A list whose items SystemUI would fetch from the app's service itself, unchecked: the named action, or
            // the same done by method name (setRemoteViewsAdapter on a StackView or an AdapterViewFlipper, or any
            // setter given an Intent). Refused whatever remote_adapter_conversion says: widgets send their items
            // inside the update (SetRemoteCollectionItemListAdapterAction, walked like any nested views).
            mUnsafe = "a collection served by the app's service";
            return;
        }
        if (sReflectionAction.isInstance(o) && sType.getInt(o) == sUriType) {
            visitUriAction(o);
            return;
        }
        if (sReflectionAction.isInstance(o) && sType.getInt(o) == sBundleType) {
            visitBundleAction(o);
            return;
        }
        if (mCollect != null) {
            collectResources(o);
        }
        for (Field field : fields(o.getClass())) {
            visit(field.get(o), depth + 1);
            if (mUnsafe != null) {
                return;
            }
        }
    }

    private void visitIcon(Icon icon) {
        if (!mImages.needsWork(icon)) {
            return;
        }
        mNeedsWork = true;
        if (mConvert) {
            final Icon safe = mImages.convert(icon);
            if (safe == null || mImages.needsWork(safe)) {
                mUnsafe = "an image that could not be replaced";
                return;
            }
            icon.setTo(safe);
        }
    }

    private void visitUriAction(Object action) throws ReflectiveOperationException {
        mNeedsWork = true;
        if (!SET_IMAGE_URI.equals(sMethodName.get(action))) {
            mUnsafe = "a link given to " + sMethodName.get(action);
            return;
        }
        if (!mConvert) {
            return;
        }
        final Uri uri = (Uri) sValue.get(action);
        final Icon icon = uri == null ? null : mImages.convert(uri);
        if (uri != null && (icon == null || mImages.needsWork(icon))) {
            mUnsafe = "an image link that could not be replaced";
            return;
        }
        // setImageURI(uri) becomes setImageIcon(bitmap) on the same view, at the same place among the actions.
        sValue.set(action, icon);
        sType.setInt(action, sIconType);
        sMethodName.set(action, SET_IMAGE_ICON);
    }

    // A Bundle given to a view: only to a messaging or call layout, only with the keys it reads, replaced by a new
    // Bundle of those values read with typed getters. Anything else drops the layout.
    private void visitBundleAction(Object action) throws ReflectiveOperationException {
        if (!BUNDLE_METHODS.contains(sMethodName.get(action))) {
            mUnsafe = "a Bundle given to " + sMethodName.get(action);
            return;
        }
        final Bundle bundle = (Bundle) sValue.get(action);
        if (bundle == null || sFiltered.contains(bundle)) {
            return;
        }
        mNeedsWork = true;
        if (!mConvert) {
            return;
        }
        final Bundle filtered;
        try {
            filtered = filter(bundle, DATA_KEYS, 0);
        } catch (RuntimeException e) {
            // A value of another type than its key wants, or a Bundle that cannot be read.
            mUnsafe = "a Bundle that cannot be read: " + e.getClass().getSimpleName();
            return;
        }
        if (filtered == null) {
            return;
        }
        sFiltered.add(filtered);
        sValue.set(action, filtered);
    }

    // A new Bundle of the known keys of {@code in}, or null (with mUnsafe set) when it holds anything else.
    private Bundle filter(Bundle in, Map<String, Kind> keys, int depth) {
        final int size = in.isParcelled() ? in.getSize() : parcelSize(in);
        if (size > MAX_BUNDLE_BYTES) {
            mUnsafe = "a Bundle of " + size + " bytes";
            return null;
        }
        final Bundle out = new Bundle();
        for (String key : in.keySet()) {
            final Kind kind = keys.get(key);
            if (kind == null) {
                if (depth > 0 && MESSAGE_EXTRAS.equals(key)) {
                    // A message's own extras: only an empty one.
                    final Bundle extras = in.getParcelable(key, Bundle.class);
                    if (extras == null || extras.isEmpty()) {
                        continue;
                    }
                }
                mUnsafe = "a Bundle with the key " + key;
                return null;
            }
            switch (kind) {
                case PERSON: {
                    final Person person = in.getParcelable(key, Person.class);
                    if (person != null) {
                        visitIcon(person.getIcon());
                        out.putParcelable(key, person);
                    }
                    break;
                }
                case ICON: {
                    final Icon icon = in.getParcelable(key, Icon.class);
                    if (icon != null) {
                        visitIcon(icon);
                        out.putParcelable(key, icon);
                    }
                    break;
                }
                case PEOPLE: {
                    final ArrayList<Person> people = in.getParcelableArrayList(key, Person.class);
                    if (people != null) {
                        for (Person person : people) {
                            if (person != null) {
                                visitIcon(person.getIcon());
                            }
                        }
                        out.putParcelableArrayList(key, people);
                    }
                    break;
                }
                case MESSAGES: {
                    if (depth > 0) {
                        mUnsafe = "messages inside a message";
                        return null;
                    }
                    final Bundle[] messages = in.getParcelableArray(key, Bundle.class);
                    if (messages != null) {
                        if (messages.length > MAX_MESSAGES) {
                            mUnsafe = "too many messages";
                            return null;
                        }
                        final Bundle[] copies = new Bundle[messages.length];
                        for (int i = 0; i < messages.length; i++) {
                            copies[i] = messages[i] == null ? null : filter(messages[i], MESSAGE_KEYS, depth + 1);
                            if (mUnsafe != null) {
                                return null;
                            }
                        }
                        out.putParcelableArray(key, copies);
                    }
                    break;
                }
                // Plain values are read with a class too: a lazy value of another type is never made, only skipped.
                case TEXT: {
                    final CharSequence text = in.getParcelable(key, CharSequence.class);
                    if (text != null) {
                        out.putCharSequence(key, text);
                    }
                    break;
                }
                case BOOLEAN: {
                    final Boolean value = in.getParcelable(key, Boolean.class);
                    if (value != null) {
                        out.putBoolean(key, value);
                    }
                    break;
                }
                case INT: {
                    final Integer value = in.getParcelable(key, Integer.class);
                    if (value != null) {
                        out.putInt(key, value);
                    }
                    break;
                }
                case LONG: {
                    final Long value = in.getParcelable(key, Long.class);
                    if (value != null) {
                        out.putLong(key, value);
                    }
                    break;
                }
            }
            if (mUnsafe != null) {
                return null;
            }
        }
        return out;
    }

    private static int parcelSize(Bundle bundle) {
        final Parcel parcel = Parcel.obtain();
        try {
            bundle.writeToParcel(parcel, 0);
            return parcel.dataSize();
        } finally {
            parcel.recycle();
        }
    }

    // Resource IDs that applying this action loads as drawables: setImageViewResource, setBackgroundResource and
    // compound drawables given by ID.
    private void collectResources(Object o) throws ReflectiveOperationException {
        if (sReflectionAction.isInstance(o) && sType.getInt(o) == sIntType) {
            final Object name = sMethodName.get(o);
            final Object value = sValue.get(o);
            if (name instanceof String && ((String) name).endsWith("Resource") && value instanceof Integer) {
                mCollect.add((Integer) value);
            }
            return;
        }
        if (o.getClass().getName().endsWith("$TextViewDrawableAction")) {
            for (String f : new String[] {"mD1", "mD2", "mD3", "mD4"}) {
                final Field field = o.getClass().getDeclaredField(f);
                field.setAccessible(true);
                final int id = field.getInt(o);
                if (id != 0) {
                    mCollect.add(id);
                }
            }
        }
    }

    /**
     * The layouts and drawable resource IDs {@code views} applies, nested layouts included: what a prewarm loads
     * ahead. Empty when the layout cannot be read.
     */
    static Targets resources(RemoteViews views) {
        final Targets targets = new Targets();
        final RemoteViewsImages walk = new RemoteViewsImages(new Images() {
            @Override
            public boolean needsWork(Icon icon) {
                return false;
            }

            @Override
            public Icon convert(Icon icon) {
                return icon;
            }

            @Override
            public Icon convert(Uri uri) {
                return null;
            }
        }, false);
        walk.mCollect = targets.drawables;
        walk.mLayouts = targets.layouts;
        walk.run(views);
        return targets;
    }

    /** What a layout loads by resource ID. */
    static final class Targets {
        final List<Integer> layouts = new ArrayList<>();
        final List<Integer> drawables = new ArrayList<>();
    }

    // The instance fields of a RemoteViews class (a subclass of RemoteViews included) up to Object.
    private static Field[] fields(Class<?> cls) {
        Field[] fields = sFields.get(cls);
        if (fields != null) {
            return fields;
        }
        final List<Field> list = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()
                        || SKIPPED_FIELDS.contains(f.getName())) {
                    continue;
                }
                f.setAccessible(true);
                list.add(f);
            }
        }
        fields = list.toArray(new Field[0]);
        sFields.put(cls, fields);
        return fields;
    }
}
