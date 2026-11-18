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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BlendMode;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.SizeF;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import de.diamaneos.decode.client.tests.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Checks that catch an upstream change the converter does not know yet, for the rebase: the shape of RemoteViews
 * (every field type of every action), every public setter of RemoteViews, the reflection handles the walker reads, the
 * fields Icon.setTo copies, the platform's own list of notification URIs ({@link Notification#visitUris}) against the
 * converted copy, and a decode tripwire over the standard templates (messaging, conversation, big picture) whose
 * views load images themselves. A failure names the class, field, method or key to look at.
 */
@RunWith(AndroidJUnit4.class)
public class RebaseGuardDeviceTest {
    private static final String RV = "android.widget.RemoteViews";
    private static final int ORACLE_WIDTH = 7;
    private static final int ORACLE_HEIGHT = 3;
    private static final String ORACLE_AUTHORITY = "de.diamaneos.decode.client.tests.none";

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String mPackage = mContext.getPackageName();

    // RemoteViews classes the walker goes into besides the actions: what a parceled layout can hold.
    private static final Set<String> HOLDERS = Set.of("BitmapCache", "ApplicationInfoCache",
            "RemoteCollectionItems", "HierarchyRootData", "RemoteCollectionCache", "RemoteResponse");
    // Fields that are null in a parceled copy (the only copy the walker sees), or never walked.
    private static final Set<String> NULL_WHEN_PARCELED = Set.of("RunnableAction.mRunnable",
            "TextViewDrawableAction.mId1", "TextViewDrawableAction.mId2", "TextViewDrawableAction.mId3",
            "TextViewDrawableAction.mId4");
    // Actions the walker refuses by name: their fields are never walked.
    private static final Set<String> REFUSED_ACTIONS = Set.of("SetDrawInstructionAction",
            "SetRemoteViewsAdapterIntent");
    // Declared field types a walk handles (leaves, images, values walked at run time, containers).
    private static final Class<?>[] KNOWN_TYPES = {
            CharSequence.class, Number.class, Boolean.class, Character.class, Enum.class, Class.class,
            Bitmap.class, ColorStateList.class, PendingIntent.class, Intent.class,
            android.content.pm.ApplicationInfo.class, RemoteInput.class, SizeF.class,
            android.util.SparseIntArray.class, android.util.SparseBooleanArray.class, android.util.IntArray.class,
            android.util.LongArray.class, java.time.Instant.class, java.time.Duration.class,
            Icon.class, Object.class, Collection.class, Map.class, android.util.SparseArray.class,
            android.util.Pair.class, RemoteViews.class, Parcelable[].class, Object[].class,
    };

    private static String shortName(Class<?> cls) {
        return cls.getName().substring(cls.getName().lastIndexOf('$') + 1);
    }

    private static boolean known(Class<?> type) {
        if (type.isPrimitive() || (type.isArray() && type.getComponentType().isPrimitive())) {
            return true;
        }
        for (Class<?> k : KNOWN_TYPES) {
            if (k == Object.class ? type == Object.class : k.isAssignableFrom(type)) {
                return true;
            }
        }
        return false;
    }

    private static List<Class<?>> nested(Class<?> cls) {
        final List<Class<?>> all = new ArrayList<>();
        for (Class<?> c : cls.getDeclaredClasses()) {
            all.add(c);
            all.addAll(nested(c));
        }
        return all;
    }

    @Test
    public void remoteViewsSchema_everyFieldTypeIsKnown() throws Exception {
        final Class<?> action = Class.forName(RV + "$Action");
        final List<Class<?>> walked = new ArrayList<>();
        walked.add(RemoteViews.class);
        for (Class<?> c : nested(RemoteViews.class)) {
            if ((action.isAssignableFrom(c) && !REFUSED_ACTIONS.contains(shortName(c)))
                    || HOLDERS.contains(shortName(c))) {
                walked.add(c);
            }
        }
        final Set<String> unknown = new TreeSet<>();
        for (Class<?> c : walked) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()
                        || Set.of("mClassCookies", "mLayoutInflaterFactory2").contains(f.getName())
                        || NULL_WHEN_PARCELED.contains(shortName(c) + "." + f.getName())) {
                    continue;
                }
                final Class<?> type = f.getType();
                if (known(type)) {
                    continue;
                }
                // Another RemoteViews class: an action, or a holder the walker knows.
                if (type.getName().startsWith(RV + "$")
                        && (action.isAssignableFrom(type) || HOLDERS.contains(shortName(type)))) {
                    continue;
                }
                unknown.add(shortName(c) + "." + f.getName() + ": " + type.getName());
            }
        }
        assertTrue("RemoteViews fields of a type the walker does not know (check them, then list them here): "
                + unknown, unknown.isEmpty());
    }

    @Test
    public void reflectionHandles_resolve_andAPlainLayoutIsKept() throws Exception {
        for (String name : new String[] {"sReflectionAction", "sMethodName", "sType", "sValue",
                "sBaseReflectionAction"}) {
            final Field f = RemoteViewsImages.class.getDeclaredField(name);
            f.setAccessible(true);
            assertNotNull("RemoteViews handle not found: " + name, f.get(null));
        }
        for (String name : new String[] {"sUriType", "sIconType", "sBundleType", "sIntType", "sIntentType"}) {
            final Field f = RemoteViewsImages.class.getDeclaredField(name);
            f.setAccessible(true);
            assertTrue("RemoteViews constant not found: " + name, f.getInt(null) >= 0);
        }
        // The fields the walk reads by name on the image actions.
        final Class<?> drawables = Class.forName(RV + "$TextViewDrawableAction");
        for (String name : new String[] {"mI1", "mI2", "mI3", "mI4"}) {
            assertEquals(name, Icon.class, drawables.getDeclaredField(name).getType());
        }
        final RemoteViews plain = new RemoteViews(mPackage, R.layout.vm_test_probe);
        plain.setImageViewResource(R.id.vm_probe, R.drawable.vm_test_red);
        plain.setTextViewText(R.id.vm_compound, "text");
        assertTrue("a plain layout is dropped", RemoteViewsImages.convert(plain, new BitmapImages()));
    }

    // Converts every image to a one-pixel bitmap, as the gate does with no time for the VM.
    private static final class BitmapImages implements RemoteViewsImages.Images {
        @Override
        public boolean needsWork(Icon icon) {
            return icon.getType() != Icon.TYPE_BITMAP && icon.getType() != Icon.TYPE_ADAPTIVE_BITMAP;
        }

        @Override
        public Icon convert(Icon icon) {
            return Icon.createWithBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888));
        }

        @Override
        public Icon convert(Uri uri) {
            return convert((Icon) null);
        }
    }

    // Setters whose layout the walker refuses on purpose (a list served by the app, a link or Intent given to a
    // view by method name, a Bundle for another method than the two it reads, draw instructions).
    private static final Set<String> REFUSED_SETTERS = Set.of("setRemoteAdapter", "setRemoteViewsAdapter",
            "setIntent", "setUri", "setBundle");

    private Object sample(Class<?> type, Method m) {
        if (type == int.class) {
            return R.id.vm_probe;
        } else if (type == boolean.class) {
            return true;
        } else if (type == float.class) {
            return 1f;
        } else if (type == long.class) {
            return 1L;
        } else if (type == double.class) {
            return 1d;
        } else if (type == short.class) {
            return (short) 1;
        } else if (type == byte.class) {
            return (byte) 1;
        } else if (type == char.class) {
            return 'a';
        } else if (type == String.class) {
            return "setAlpha";
        } else if (type == CharSequence.class) {
            return "text";
        } else if (type == Icon.class) {
            return oracleIcon(1);
        } else if (type == Uri.class) {
            return Uri.parse("content://" + ORACLE_AUTHORITY + "/setter.png");
        } else if (type == Bitmap.class) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        } else if (type == ColorStateList.class) {
            return ColorStateList.valueOf(0xff000000);
        } else if (type == PendingIntent.class) {
            return PendingIntent.getActivity(mContext, 0, new Intent(), PendingIntent.FLAG_IMMUTABLE);
        } else if (type == Intent.class) {
            return new Intent();
        } else if (type == Bundle.class) {
            return new Bundle();
        } else if (type == RemoteViews.class) {
            return new RemoteViews(mPackage, R.layout.vm_test_child);
        } else if (type == RemoteViews.RemoteResponse.class) {
            return RemoteViews.RemoteResponse.fromPendingIntent((PendingIntent) sample(PendingIntent.class, m));
        } else if (type == RemoteViews.RemoteCollectionItems.class) {
            return new RemoteViews.RemoteCollectionItems.Builder().build();
        } else if (type == ArrayList.class) {
            return new ArrayList<>();
        } else if (type == SizeF.class) {
            return new SizeF(1, 1);
        } else if (type == BlendMode.class) {
            return BlendMode.SRC_IN;
        } else if (type.isEnum()) {
            return type.getEnumConstants()[0];
        } else if (type == int[].class) {
            return new int[] {R.id.vm_probe};
        } else if (type.isArray() && type.getComponentType().isPrimitive()) {
            return java.lang.reflect.Array.newInstance(type.getComponentType(), 1);
        } else if (type == RemoteInput[].class) {
            return new RemoteInput[] {new RemoteInput.Builder("key").setLabel("label").build()};
        }
        return null;
    }

    @Test
    public void publicSetterCorpus_onlyTheRefusalsDropTheLayout() throws Exception {
        final Set<String> unexpected = new TreeSet<>();
        final Set<String> noSample = new TreeSet<>();
        int tried = 0;
        for (Method m : RemoteViews.class.getMethods()) {
            final String name = m.getName();
            if (Modifier.isStatic(m.getModifiers()) || m.getDeclaringClass() != RemoteViews.class
                    || !(name.startsWith("set") || name.startsWith("add") || name.startsWith("remove"))) {
                continue;
            }
            final Class<?>[] types = m.getParameterTypes();
            if (types.length == 0 || types[0] != int.class) {
                continue; // not an action on a view (setApplyFlags, setLightBackgroundLayoutId and the like)
            }
            final Object[] args = new Object[types.length];
            boolean complete = true;
            for (int i = 0; i < types.length; i++) {
                args[i] = sample(types[i], m);
                if (args[i] == null) {
                    noSample.add(name + ": " + types[i].getName());
                    complete = false;
                }
            }
            if (!complete) {
                continue;
            }
            final RemoteViews views = new RemoteViews(mPackage, R.layout.vm_test_probe);
            try {
                m.invoke(views, args);
            } catch (ReflectiveOperationException | RuntimeException e) {
                continue; // the sample does not fit this setter's checks: nothing was added
            }
            tried++;
            final RemoteViews copy = RemoteViewsImages.deepCopy(views);
            if (!RemoteViewsImages.convert(copy, new BitmapImages()) && !REFUSED_SETTERS.contains(name)) {
                unexpected.add(name + java.util.Arrays.toString(types));
            }
        }
        assertTrue("no setter was tried", tried > 20);
        assertTrue("setters with a parameter type the corpus has no sample for (add one): " + noSample,
                noSample.isEmpty());
        assertTrue("layouts dropped by a setter that is not a known refusal: " + unexpected, unexpected.isEmpty());
    }

    @Test
    public void iconFields_areExactlyWhatSetToCopies() {
        final Set<String> fields = new TreeSet<>();
        for (Field f : Icon.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
                fields.add(f.getName());
            }
        }
        assertEquals("Icon has fields Icon.setTo does not copy (or no longer has some it copies)",
                new TreeSet<>(Set.of("mType", "mObj1", "mCachedAshmem", "mString1", "mInt1", "mInt2",
                        "mUseMonochrome", "mInsetScale", "mTintList", "mBlendMode")), fields);
    }

    private static int sOracle = 100;

    // A valid 7 x 3 PNG, different for each n: what Android would decode if it ever got it.
    private static Icon oracleIcon(int n) {
        final Bitmap bitmap = Bitmap.createBitmap(ORACLE_WIDTH, ORACLE_HEIGHT, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xff000000 | n);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        final byte[] png = out.toByteArray();
        return Icon.createWithData(png, 0, png.length);
    }

    private static Uri oracleUri(int n) {
        return Uri.parse("content://" + ORACLE_AUTHORITY + "/oracle/" + n);
    }

    private NotificationImageGate gate() {
        // No time for the VM: every app image comes out empty, and nothing waits.
        return NotificationImageGate.forNotification(mContext, mPackage, mContext.getUserId(), 64, 512, 512,
                new ImageBudget(0, ImageBudget.MAX_IMAGES, ImageBudget.MAX_PIXELS));
    }

    private static <T> T onImageThread(Callable<T> callable) throws Exception {
        final FutureTask<T> task = new FutureTask<>(callable);
        ImageThreads.execute("de.diamaneos.decode.client.tests", task);
        return task.get(30, TimeUnit.SECONDS);
    }

    private static <T> T onWorker(Callable<T> callable) throws Exception {
        final android.os.HandlerThread thread = new android.os.HandlerThread("vm-guard-test");
        thread.start();
        try {
            final FutureTask<T> task = new FutureTask<>(callable);
            new android.os.Handler(thread.getLooper()).post(task);
            return task.get(30, TimeUnit.SECONDS);
        } finally {
            thread.quitSafely();
        }
    }

    // Every EXTRA_ key of Notification, read from its constants: a key upstream adds is covered without a list here.
    private static List<String> extraKeys() throws IllegalAccessException {
        final List<String> keys = new ArrayList<>();
        for (Field f : Notification.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class
                    && f.getName().startsWith("EXTRA_")) {
                keys.add((String) f.get(null));
            }
        }
        return keys;
    }

    private Notification withUriImagesEverywhere(boolean persons) throws Exception {
        int n = 0;
        final Bundle extras = new Bundle();
        for (String key : extraKeys()) {
            n++;
            if (persons) {
                extras.putParcelable(key,
                        new Person.Builder().setName("p" + n).setIcon(Icon.createWithContentUri(oracleUri(n)))
                                .build());
            } else {
                extras.putParcelable(key, Icon.createWithContentUri(oracleUri(n)));
            }
        }
        final Notification.Action action = new Notification.Action.Builder(
                Icon.createWithContentUri(oracleUri(++n)), "act",
                PendingIntent.getActivity(mContext, 0, new Intent(), PendingIntent.FLAG_IMMUTABLE)).build();
        final Notification publicVersion = new Notification.Builder(mContext, "test")
                .setSmallIcon(Icon.createWithContentUri(oracleUri(++n)))
                .setLargeIcon(Icon.createWithContentUri(oracleUri(++n)))
                .build();
        final Notification.BubbleMetadata bubble = new Notification.BubbleMetadata.Builder(
                PendingIntent.getActivity(mContext, 0, new Intent(), PendingIntent.FLAG_IMMUTABLE | PendingIntent
                        .FLAG_UPDATE_CURRENT),
                Icon.createWithContentUri(oracleUri(++n))).build();
        final Notification notification = new Notification.Builder(mContext, "test")
                .setSmallIcon(Icon.createWithContentUri(oracleUri(++n)))
                .setLargeIcon(Icon.createWithContentUri(oracleUri(++n)))
                .addAction(action)
                .setPublicVersion(publicVersion)
                .setBubbleMetadata(bubble)
                .build();
        notification.extras.putAll(extras);
        notification.sound = null;
        return notification;
    }

    @Test
    public void notificationOracle_platformVisitorFindsNoAppImageLinkInTheCopy() throws Exception {
        for (boolean persons : new boolean[] {false, true}) {
            final Notification original = withUriImagesEverywhere(persons);
            final Set<Uri> before = new HashSet<>();
            original.visitUris(before::add);
            assertFalse("the platform's visitor found none of the links", before.isEmpty());
            final NotificationImageGate gate = gate();
            final Notification copy = onImageThread(() -> gate.sanitizedCopy(original));
            final Set<Uri> left = new TreeSet<>(java.util.Comparator.comparing(Uri::toString));
            copy.visitUris(uri -> {
                if (ORACLE_AUTHORITY.equals(uri.getAuthority())) {
                    left.add(uri);
                }
            });
            assertTrue((persons ? "persons" : "icons") + ": app image links the platform still finds in the copy"
                    + " (a place the gate does not convert): " + left, left.isEmpty());
        }
    }

    // Every drawable in a view tree, layers and insets opened, compound drawables and backgrounds included.
    private static void drawables(View view, List<Drawable> out) {
        add(view.getBackground(), out);
        if (view instanceof ImageView) {
            add(((ImageView) view).getDrawable(), out);
        }
        if (view instanceof TextView) {
            for (Drawable d : ((TextView) view).getCompoundDrawablesRelative()) {
                add(d, out);
            }
        }
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                drawables(((ViewGroup) view).getChildAt(i), out);
            }
        }
    }

    private static void add(Drawable d, List<Drawable> out) {
        if (d == null) {
            return;
        }
        out.add(d);
        if (d instanceof LayerDrawable) {
            for (int i = 0; i < ((LayerDrawable) d).getNumberOfLayers(); i++) {
                add(((LayerDrawable) d).getDrawable(i), out);
            }
        } else if (d instanceof InsetDrawable) {
            add(((InsetDrawable) d).getDrawable(), out);
        }
    }

    private View inflateAll(Context context, Notification copy) {
        final Notification.Builder builder = Notification.Builder.recoverBuilder(context, copy);
        final FrameLayout root = new FrameLayout(mContext);
        for (RemoteViews views : new RemoteViews[] {builder.createContentView(), builder.createBigContentView(),
                builder.createHeadsUpContentView()}) {
            if (views != null) {
                root.addView(views.apply(context, root));
            }
        }
        return root;
    }

    @Test
    public void tripwire_standardTemplates_nothingOfTheAppIsDecodedOnAndroid() throws Exception {
        final Person me = new Person.Builder().setName("me").setIcon(oracleIcon(sOracle++)).build();
        final Person other = new Person.Builder().setName("other").setIcon(oracleIcon(sOracle++)).build();
        final Notification messaging = new Notification.Builder(mContext, "test")
                .setSmallIcon(oracleIcon(sOracle++))
                .setLargeIcon(oracleIcon(sOracle++))
                .setStyle(new Notification.MessagingStyle(me)
                        .setConversationTitle("group")
                        .setGroupConversation(true)
                        .addMessage(new Notification.MessagingStyle.Message("hi", 1, other))
                        .addMessage(new Notification.MessagingStyle.Message("you", 2, me)))
                .build();
        final Notification call = new Notification.Builder(mContext, "test")
                .setSmallIcon(oracleIcon(sOracle++))
                .setStyle(Notification.CallStyle.forIncomingCall(other,
                        PendingIntent.getActivity(mContext, 1, new Intent(), PendingIntent.FLAG_IMMUTABLE),
                        PendingIntent.getActivity(mContext, 2, new Intent(), PendingIntent.FLAG_IMMUTABLE)))
                .build();
        final Notification picture = new Notification.Builder(mContext, "test")
                .setSmallIcon(oracleIcon(sOracle++))
                .setLargeIcon(oracleIcon(sOracle++))
                .setStyle(new Notification.BigPictureStyle()
                        .bigPicture(oracleIcon(sOracle++))
                        .bigLargeIcon(oracleIcon(sOracle++)))
                .build();
        for (Notification original : new Notification[] {messaging, call, picture}) {
            final NotificationImageGate gate = gate();
            final Context context = gate.packageContext(mContext);
            final Notification copy = onImageThread(() -> gate.sanitizedCopy(original));
            // Inflated like SystemUI's inflation thread: a looper, and no wait for the VM.
            final View root = onWorker(() -> inflateAll(context, copy));
            final List<Drawable> found = new ArrayList<>();
            drawables(root, found);
            for (Drawable d : found) {
                if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
                    final Bitmap b = ((BitmapDrawable) d).getBitmap();
                    assertFalse("decoded on Android in a " + original.extras.getString(Notification.EXTRA_TEMPLATE)
                            + " template: " + d, b.getWidth() == ORACLE_WIDTH && b.getHeight() == ORACLE_HEIGHT);
                }
            }
        }
    }
}
