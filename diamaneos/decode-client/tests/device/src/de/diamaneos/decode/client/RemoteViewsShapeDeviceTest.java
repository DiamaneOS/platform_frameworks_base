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

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.widget.RemoteViews;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import de.diamaneos.decode.client.tests.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The shape of RemoteViews the walker ({@link RemoteViewsImages}) was reviewed for, pinned: every class the walker goes
 * into, every instance field with its type, and the int constants that select what an action does (the action tags
 * and value types of RemoteViews, every constant of a walked class).
 *
 * <p>The walker reads actions generically and refuses values it does not know, so most upstream changes make it drop
 * a layout rather than miss an image. What it cannot see: an image reached through a primitive field (a resource ID
 * of a new action), a byte array, or a new reflection value type whose value is a known leaf. So any change to the
 * shape, an action type, a field or a constant added, removed or retyped upstream, fails here until someone has
 * checked the walker against it and written the new shape to res/raw/remoteviews_shape.txt
 * ({@code tools/remoteviews_shape.py} prints it from the new base's RemoteViews.java).
 */
@RunWith(AndroidJUnit4.class)
public class RemoteViewsShapeDeviceTest {
    private static final String RV = "android.widget.RemoteViews";
    // RemoteViews classes the walker goes into besides the actions: what a parceled layout can hold.
    private static final Set<String> HOLDERS = Set.of("BitmapCache", "ApplicationInfoCache",
            "RemoteCollectionItems", "HierarchyRootData", "RemoteCollectionCache", "RemoteResponse");

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private static String shortName(Class<?> cls) {
        final String name = cls.getName();
        return name.substring(Math.max(name.lastIndexOf('$'), name.lastIndexOf('.')) + 1);
    }

    private static List<Class<?>> nested(Class<?> cls) {
        final List<Class<?>> all = new ArrayList<>();
        for (Class<?> c : cls.getDeclaredClasses()) {
            all.add(c);
            all.addAll(nested(c));
        }
        return all;
    }

    // Constants of RemoteViews itself that say what an action is: its tags and the value types of reflection actions.
    private static boolean actionConstant(Class<?> owner, String name) {
        return owner != RemoteViews.class || name.endsWith("_TAG") || name.startsWith("SET_")
                || name.startsWith("VALUE_TYPE_");
    }

    /** The shape of the RemoteViews this phone runs, in the format of the pinned file. */
    static TreeSet<String> currentShape() throws Exception {
        final Class<?> action = Class.forName(RV + "$Action");
        final List<Class<?>> walked = new ArrayList<>();
        walked.add(RemoteViews.class);
        for (Class<?> c : nested(RemoteViews.class)) {
            if (action.isAssignableFrom(c) || HOLDERS.contains(shortName(c))) {
                walked.add(c);
            }
        }
        final TreeSet<String> lines = new TreeSet<>();
        for (Class<?> c : walked) {
            final String name = shortName(c);
            final Class<?> parent = c.getSuperclass();
            lines.add("class " + name + " extends "
                    + (parent == null || parent == Object.class ? "Object" : shortName(parent)));
            for (Field f : c.getDeclaredFields()) {
                if (f.isSynthetic()) {
                    continue;
                }
                final int mods = f.getModifiers();
                if (!Modifier.isStatic(mods)) {
                    lines.add(name + "." + f.getName() + ": " + f.getType().getSimpleName());
                } else if (Modifier.isFinal(mods) && f.getType() == int.class && actionConstant(c, f.getName())) {
                    f.setAccessible(true);
                    lines.add(name + "." + f.getName() + " = " + f.getInt(null));
                }
            }
        }
        return lines;
    }

    private TreeSet<String> pinnedShape() throws Exception {
        final TreeSet<String> lines = new TreeSet<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                mContext.getResources().openRawResource(R.raw.remoteviews_shape), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                if (!line.isEmpty() && !line.startsWith("#")) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    @Test
    public void remoteViews_hasTheShapeTheWalkerWasReviewedFor() throws Exception {
        final TreeSet<String> pinned = pinnedShape();
        final TreeSet<String> now = currentShape();
        assertTrue("the pinned shape is empty", pinned.size() > 100);
        final TreeSet<String> added = new TreeSet<>(now);
        added.removeAll(pinned);
        final TreeSet<String> gone = new TreeSet<>(pinned);
        gone.removeAll(now);
        if (!added.isEmpty() || !gone.isEmpty()) {
            fail("RemoteViews changed since the walker was reviewed. Added upstream: " + added
                    + ". Gone upstream: " + gone + ". Check RemoteViewsImages against each line (an image in a"
                    + " primitive field, a byte array or a new value type escapes the walk), then write the new shape"
                    + " with tools/remoteviews_shape.py to tests/device/res/raw/remoteviews_shape.txt");
        }
    }
}
