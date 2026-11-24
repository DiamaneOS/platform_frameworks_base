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

import static org.junit.Assume.assumeTrue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Person;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.UserHandle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Steps of the phone checks of the UI fixes, run by a host script one at a time with {@code -e proof NAME}: each
 * posts what SystemUI or Settings then shows, and the host reads the screen or the heap. Skipped in a normal run.
 * Everything is silent: low or silent channels, no sound, no vibration.
 */
@RunWith(AndroidJUnit4.class)
public class PhoneProofsDeviceTest {
    private static final String QUIET = "proofs";
    private static final String CHAT = "proof-chat";
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final NotificationManager mNotifications = mContext.getSystemService(NotificationManager.class);
    private final int mUser = Process.myUserHandle().getIdentifier();

    private static void assumeProof(String name) {
        assumeTrue("not this phone check", name.equals(InstrumentationRegistry.getArguments().getString("proof")));
    }

    private static long arg(String name, long otherwise) {
        final String value = InstrumentationRegistry.getArguments().getString(name);
        return value == null ? otherwise : Long.parseLong(value);
    }

    private static Bitmap square(int color) {
        final Bitmap bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        return bitmap;
    }

    private static byte[] png(int color) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        square(color).compress(Bitmap.CompressFormat.PNG, 100, out);
        return out.toByteArray();
    }

    private void channels() {
        final NotificationChannel quiet = new NotificationChannel(QUIET, "Phone checks",
                NotificationManager.IMPORTANCE_LOW);
        final NotificationChannel chat = new NotificationChannel(CHAT, "Phone check chat",
                NotificationManager.IMPORTANCE_DEFAULT);
        chat.setSound(null, null);
        chat.enableVibration(false);
        chat.setAllowBubbles(true);
        mNotifications.createNotificationChannel(quiet);
        mNotifications.createNotificationChannel(chat);
    }

    /** H3: many updates of notifications shown as they are (own pixels only), then all dismissed. */
    @Test
    public void flood() throws Exception {
        assumeProof("flood");
        channels();
        final long count = arg("count", 200);
        final long gap = arg("gapMs", 220);
        for (int i = 0; i < count; i++) {
            mNotifications.notify(100 + i % 20, new Notification.Builder(mContext, QUIET)
                    .setSmallIcon(Icon.createWithBitmap(square(0xff808080)))
                    .setContentTitle("proof flood")
                    .setContentText("h3-marker-" + i + "-end")
                    .build());
            Thread.sleep(gap);
        }
        Thread.sleep(3_000);
        mNotifications.cancelAll();
    }

    /** H4: an image clip whose preview comes late, then at once a sensitive image clip. */
    @Test
    public void clip() throws Exception {
        assumeProof("clip");
        final ClipboardManager clipboard = mContext.getSystemService(ClipboardManager.class);
        final ClipData older = new ClipData(new ClipDescription("older image", new String[] {"image/png"}),
                new ClipData.Item(UserImageProvider.image(0xffff0000, arg("delayMs", 2_000))));
        final ClipDescription sensitive = new ClipDescription("sensitive image", new String[] {"image/png"});
        final PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
        sensitive.setExtras(extras);
        final ClipData newer = new ClipData(sensitive,
                new ClipData.Item(UserImageProvider.image(0xff00ff00, 0)));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> clipboard.setPrimaryClip(older));
        Thread.sleep(arg("gapMs", 400));
        final String replacement = InstrumentationRegistry.getArguments().getString("replacement", "sensitive");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if ("clear".equals(replacement)) clipboard.clearPrimaryClip();
            else clipboard.setPrimaryClip(newer);
        });
    }

    /** H7: notifications of this user with a data icon and a URI icon, for the Settings notification log. */
    @Test
    public void icons() {
        assumeProof("icons");
        channels();
        final byte[] data = png(UserImageProvider.colorOf(mUser));
        mNotifications.notify(23, new Notification.Builder(mContext, QUIET)
                .setSmallIcon(Icon.createWithResource(mContext, de.diamaneos.decode.client.tests.R.drawable.vm_test_red))
                .setContentTitle("resource icon of user " + mUser)
                .build());
        mNotifications.notify(21, new Notification.Builder(mContext, QUIET)
                .setSmallIcon(Icon.createWithData(data, 0, data.length))
                .setContentTitle("data icon of user " + mUser)
                .build());
        mNotifications.notify(22, new Notification.Builder(mContext, QUIET)
                .setSmallIcon(Icon.createWithContentUri(UserImageProvider.IMAGE))
                .setContentTitle("URI icon of user " + mUser)
                .build());
    }

    /**
     * H2 and M5: a conversation of this user whose message has an image ({@link UserImageProvider} by a URI without
     * a user), with a shortcut icon, as a bubble with {@code -e bubble 1}.
     */
    @Test
    public void conversation() {
        assumeProof("conversation");
        channels();
        final Person me = new Person.Builder().setName("Me").build();
        final Person sender = new Person.Builder().setName("Proof sender of user " + mUser).setKey("proof").build();
        final ShortcutInfo shortcut = new ShortcutInfo.Builder(mContext, CHAT)
                .setShortLabel("Proof chat " + mUser)
                .setLongLived(true)
                .setPerson(sender)
                .setIcon(Icon.createWithBitmap(square(0xff00a000)))
                .setIntent(new Intent(mContext, ProofFormActivity.class).setAction(Intent.ACTION_VIEW))
                .build();
        mContext.getSystemService(ShortcutManager.class).pushDynamicShortcut(shortcut);
        final Notification.Builder builder = new Notification.Builder(mContext, CHAT)
                .setSmallIcon(Icon.createWithBitmap(square(0xff404040)))
                .setShortcutId(CHAT)
                .setStyle(new Notification.MessagingStyle(me)
                        .addMessage(new Notification.MessagingStyle.Message("picture",
                                System.currentTimeMillis(), sender)
                                .setData("image/png", UserImageProvider.IMAGE)));
        if (arg("bubble", 0) == 1) {
            builder.setBubbleMetadata(new Notification.BubbleMetadata.Builder(CHAT).build());
        }
        mNotifications.notify(7, builder.build());
    }

    /**
     * M6: this user's media session plays, its notification's icons in the user's colour, for {@code holdMs}. With
     * {@code -e dialogUser N} the output dialog of this app opens for user N and this session.
     */
    @Test
    public void media() throws Exception {
        assumeProof("media");
        channels();
        final AtomicReference<MediaSession> made = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final MediaSession session = new MediaSession(mContext, "proof-" + mUser);
            session.setMetadata(new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Proof media of user " + mUser)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "phone check")
                    .build());
            session.setPlaybackState(new PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PAUSE)
                    .build());
            session.setActive(true);
            made.set(session);
        });
        final MediaSession session = made.get();
        final int color = UserImageProvider.colorOf(mUser);
        if (arg("postNotification", 1) != 0) mNotifications.notify(31, new Notification.Builder(mContext, QUIET)
                .setSmallIcon(Icon.createWithBitmap(square(color)))
                .setLargeIcon(Icon.createWithBitmap(square(color)))
                .setContentTitle("Proof media of user " + mUser)
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()))
                .build());
        final long dialogUser = arg("dialogUser", -1);
        if (dialogUser >= 0) {
            Thread.sleep(arg("dialogAfterMs", 3_000));
            mContext.sendBroadcast(new Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                    .setPackage("com.android.systemui")
                    .putExtra("package_name", mContext.getPackageName())
                    .putExtra("user_handle", UserHandle.of((int) dialogUser)));
        }
        Thread.sleep(arg("holdMs", 60_000));
        mNotifications.cancel(31);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(session::release);
    }

    /** Clean up: every notification, shortcut and channel of the phone checks of this user. */
    @Test
    public void cleanUp() {
        assumeProof("cleanUp");
        mNotifications.cancelAll();
        mContext.getSystemService(ShortcutManager.class).removeAllDynamicShortcuts();
        mContext.getSystemService(ShortcutManager.class).removeLongLivedShortcuts(java.util.List.of(CHAT));
        mNotifications.deleteNotificationChannel(QUIET);
        mNotifications.deleteNotificationChannel(CHAT);
    }
}
