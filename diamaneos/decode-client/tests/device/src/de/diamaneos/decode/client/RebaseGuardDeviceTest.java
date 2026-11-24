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
 *
 * <p>For the native parsing survey (private-scripts rebase-checks/native-parsing) it also pins what the image hooks
 * switch on: the Icon types, the Icon factories, the notification extras and the image-carrying Intent extras. A new
 * one fails here before an app can hand it in: check every hook that reads it, then add it to the list.
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
        } else if (type == List.class) {
            // setCharSequenceList: a list of text, parceled as CharSequences only.
            return new ArrayList<CharSequence>(List.of("text", "more text"));
        } else if (type == java.time.Instant.class) {
            return java.time.Instant.ofEpochMilli(1);
        } else if (type == java.time.Duration.class) {
            return java.time.Duration.ofSeconds(1);
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

    // Extras that Notification.visitUris reads with getString and parses as a link.
    private static final Set<String> STRING_URI_EXTRAS = Set.of(Notification.EXTRA_BACKGROUND_IMAGE_URI);

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
            if (STRING_URI_EXTRAS.contains(key)) {
                // A String extra the platform's visitor parses (an Icon there makes it throw before any copy is
                // made). The gate never writes it, and nothing on the phone loads an image from it.
                extras.putString(key, "content://" + mPackage + ".background/" + n);
                continue;
            }
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
            // The bubble icon is not the gate's: bubbles are made from the original notification, and the Shell's
            // Bubble.withoutAppImage drops a URI, data or app resource icon there before anything loads it.
            final Uri bubble = original.getBubbleMetadata().getIcon().getUri();
            final Set<Uri> left = new TreeSet<>(java.util.Comparator.comparing(Uri::toString));
            copy.visitUris(uri -> {
                // The platform hands the visitor a null sound as it is.
                if (uri != null && !uri.equals(bubble) && ORACLE_AUTHORITY.equals(uri.getAuthority())) {
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

    // The Icon types the hooks switch on (VmIconLoads, IconGating, RemoteViewsImages, NotificationImageGate, AppIcons,
    // WidgetImages, the share sheet's ComposeIcon). Bitmap types carry pixels, resource types go through the image
    // gate, data and URI types are decoded in the VM.
    private static final Map<String, Integer> ICON_TYPES = Map.of("TYPE_BITMAP", 1, "TYPE_RESOURCE", 2,
            "TYPE_DATA", 3, "TYPE_URI", 4, "TYPE_ADAPTIVE_BITMAP", 5, "TYPE_URI_ADAPTIVE_BITMAP", 6);

    // Every static factory of Icon: a new one may make a new kind of icon the hooks do not know.
    private static final Set<String> ICON_FACTORIES = Set.of("createFromStream", "createWithResource",
            "createWithResourceAdaptiveDrawable", "createWithBitmap", "createWithAdaptiveBitmap", "createWithData",
            "createWithContentUri", "createWithAdaptiveBitmapContentUri", "createWithFilePath");

    // Every notification extra the notification image gate was written against. The ones that carry images (Icon,
    // Bitmap, Uri, Person, Message) are converted, the rest carry none.
    private static final Set<String> NOTIFICATION_EXTRAS = Set.of("EXTRA_ALLOW_DURING_SETUP", "EXTRA_ANSWER_COLOR",
            "EXTRA_ANSWER_INTENT", "EXTRA_APP_SUMMARIZATION", "EXTRA_AUDIO_CONTENTS_URI",
            "EXTRA_BACKGROUND_IMAGE_URI", "EXTRA_BIG_TEXT", "EXTRA_BUILDER_APPLICATION_INFO", "EXTRA_CALL_IS_VIDEO",
            "EXTRA_CALL_PERSON", "EXTRA_CALL_TYPE", "EXTRA_CHANNEL_GROUP_ID", "EXTRA_CHANNEL_ID",
            "EXTRA_CHRONOMETER_COUNT_DOWN", "EXTRA_COLORIZED", "EXTRA_COMPACT_ACTIONS", "EXTRA_CONTAINS_CUSTOM_VIEW",
            "EXTRA_CONTAINS_SUMMARIZATION", "EXTRA_CONTENT_DESCRIPTION", "EXTRA_CONVERSATION_ICON",
            "EXTRA_CONVERSATION_TITLE", "EXTRA_CONVERSATION_UNREAD_MESSAGE_COUNT", "EXTRA_DECLINE_COLOR",
            "EXTRA_DECLINE_INTENT", "EXTRA_FOREGROUND_APPS", "EXTRA_HANG_UP_INTENT", "EXTRA_HIDE_SMART_REPLIES",
            "EXTRA_HIDE_STATUS_BAR_NOTIFICATION", "EXTRA_HISTORIC_MESSAGES", "EXTRA_INFO_TEXT", "EXTRA_IS_ANIMATED",
            "EXTRA_IS_GROUP_CONVERSATION", "EXTRA_LARGE_ICON", "EXTRA_LARGE_ICON_BIG", "EXTRA_MEDIA_REMOTE_DEVICE",
            "EXTRA_MEDIA_REMOTE_ICON", "EXTRA_MEDIA_REMOTE_INTENT", "EXTRA_MEDIA_SESSION", "EXTRA_MESSAGES",
            "EXTRA_MESSAGING_PERSON", "EXTRA_NOTIFICATION_ID", "EXTRA_NOTIFICATION_TAG", "EXTRA_PEOPLE",
            "EXTRA_PEOPLE_LIST", "EXTRA_PICTURE", "EXTRA_PICTURE_CONTENT_DESCRIPTION", "EXTRA_PICTURE_ICON",
            "EXTRA_PREFER_SMALL_ICON", "EXTRA_PROGRESS", "EXTRA_PROGRESS_END_ICON", "EXTRA_PROGRESS_INDETERMINATE",
            "EXTRA_PROGRESS_MAX", "EXTRA_PROGRESS_POINTS", "EXTRA_PROGRESS_SEGMENTS", "EXTRA_PROGRESS_START_ICON",
            "EXTRA_PROGRESS_TRACKER_ICON", "EXTRA_PROJECTED_EXTENDER", "EXTRA_REBUILD_BIG_CONTENT_VIEW_ACTION_COUNT",
            "EXTRA_REBUILD_CONTENT_VIEW_ACTION_COUNT", "EXTRA_REBUILD_HEADS_UP_CONTENT_VIEW_ACTION_COUNT",
            "EXTRA_REDUCED_IMAGES", "EXTRA_REMOTE_INPUT_DRAFT", "EXTRA_REMOTE_INPUT_HISTORY",
            "EXTRA_REMOTE_INPUT_HISTORY_ITEMS", "EXTRA_REQUEST_PROMOTED_ONGOING", "EXTRA_SELF_DISPLAY_NAME",
            "EXTRA_SHORT_CRITICAL_TEXT", "EXTRA_SHOW_BIG_PICTURE_WHEN_COLLAPSED", "EXTRA_SHOW_CHRONOMETER",
            "EXTRA_SHOW_REMOTE_INPUT_SPINNER", "EXTRA_SHOW_WHEN", "EXTRA_SMALL_ICON", "EXTRA_STYLED_BY_PROGRESS",
            "EXTRA_SUBSTITUTE_APP_NAME", "EXTRA_SUB_TEXT", "EXTRA_SUMMARIZED_CONTENT", "EXTRA_SUMMARY_TEXT",
            "EXTRA_TEMPLATE", "EXTRA_TEXT", "EXTRA_TEXT_LINES", "EXTRA_TITLE", "EXTRA_TITLE_BIG",
            "EXTRA_VERIFICATION_ICON", "EXTRA_VERIFICATION_TEXT");

    // Intent extras whose name says they may carry an image or a link to one: the share sheet's previews, custom
    // actions and direct share targets, legacy shortcut icons. Any new one with such a name fails below.
    private static final java.util.regex.Pattern IMAGE_WORDS = java.util.regex.Pattern.compile(
            "ICON|IMAGE|PICTURE|PHOTO|THUMBNAIL|BITMAP|STREAM|CHOOSER|SHORTCUT|AVATAR|LOGO|ARTWORK|PREVIEW|"
                    + "WALLPAPER|URI");
    private static final Set<String> INTENT_IMAGE_EXTRAS = Set.of("EXTRA_CHOOSER_ADDITIONAL_CONTENT_URI",
            "EXTRA_CHOOSER_CONTENT_TYPE_HINT", "EXTRA_CHOOSER_CUSTOM_ACTIONS", "EXTRA_CHOOSER_FOCUSED_ITEM_POSITION",
            "EXTRA_CHOOSER_MODIFY_SHARE_ACTION", "EXTRA_CHOOSER_REFINEMENT_INTENT_SENDER", "EXTRA_CHOOSER_RESULT",
            "EXTRA_CHOOSER_RESULT_INTENT_SENDER", "EXTRA_CHOOSER_TARGETS", "EXTRA_ORIGINATING_URI",
            "EXTRA_SHORTCUT_ICON", "EXTRA_SHORTCUT_ICON_RESOURCE", "EXTRA_SHORTCUT_ID", "EXTRA_SHORTCUT_INTENT",
            "EXTRA_SHORTCUT_NAME", "EXTRA_STREAM");

    private static Set<String> extraNames(Class<?> cls) {
        final Set<String> names = new TreeSet<>();
        for (Field f : cls.getFields()) {
            final int m = f.getModifiers();
            if (Modifier.isStatic(m) && Modifier.isFinal(m) && f.getType() == String.class
                    && f.getName().startsWith("EXTRA_")) {
                names.add(f.getName());
            }
        }
        return names;
    }

    @Test
    public void iconTypes_areTheKnownSix() throws Exception {
        final Map<String, Integer> found = new java.util.TreeMap<>();
        for (Field f : Icon.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == int.class && f.getName().startsWith("TYPE_")) {
                f.setAccessible(true);
                found.put(f.getName(), f.getInt(null));
            }
        }
        assertEquals("Icon types changed (check every hook that switches on the type, and the survey baseline)",
                new java.util.TreeMap<>(ICON_TYPES), found);
    }

    @Test
    public void iconFactories_areKnown() {
        final Set<String> unknown = new TreeSet<>();
        for (Method m : Icon.class.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && m.getReturnType() == Icon.class
                    && !ICON_FACTORIES.contains(m.getName()) && !m.isSynthetic()) {
                unknown.add(m.getName());
            }
        }
        assertTrue("Icon factories the hooks do not know (what kind of icon do they make?): " + unknown,
                unknown.isEmpty());
    }

    @Test
    public void notificationExtras_noNewOne() {
        final Set<String> found = extraNames(Notification.class);
        found.removeAll(NOTIFICATION_EXTRAS);
        assertTrue("New notification extras (does one carry an Icon, Bitmap, Uri, Person or Message? Then teach "
                + "NotificationImageGate and RemoteViewsImages, and add it here): " + found, found.isEmpty());
    }

    @Test
    public void intentImageExtras_noNewOne() {
        final Set<String> found = new TreeSet<>();
        for (String name : extraNames(Intent.class)) {
            if (IMAGE_WORDS.matcher(name).find() && !INTENT_IMAGE_EXTRAS.contains(name)) {
                found.add(name);
            }
        }
        assertTrue("New Intent extras that may carry an image or a link to one (check the share sheet, the launcher "
                + "and the survey baseline, then add them here): " + found, found.isEmpty());
    }
}
