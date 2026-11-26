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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.Person;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Parcel;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * A conversation as SystemUI gets it, through a parcel from the notification service: every message keeps its text,
 * sender name, time and image link, whatever happens to the pictures. Holds with or without the decode VM.
 */
@RunWith(AndroidJUnit4.class)
public class ConversationDeviceTest {
    private static final Uri PICTURE = Uri.parse("content://de.diamaneos.decode.client.tests.none/picture.png");

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final String mPackage = mContext.getPackageName();

    private static Icon dataIcon() {
        final Bitmap bitmap = Bitmap.createBitmap(7, 3, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xff884422);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        final byte[] png = out.toByteArray();
        return Icon.createWithData(png, 0, png.length);
    }

    // As the test app posts it (vmimages notification 2), then through a parcel as SystemUI receives it.
    private Notification received() {
        final Person me = new Person.Builder().setName("Me").build();
        final Person other = new Person.Builder().setName("Other").setIcon(dataIcon()).build();
        final Notification.MessagingStyle style = new Notification.MessagingStyle(me)
                .setConversationTitle("Chat")
                .addMessage(new Notification.MessagingStyle.Message("hello", 1000, other))
                .addMessage(new Notification.MessagingStyle.Message("a picture", 2000, other)
                        .setData("image/png", PICTURE))
                .addHistoricMessage(new Notification.MessagingStyle.Message("earlier", 500, other));
        final Notification posted = new Notification.Builder(mContext, "test")
                .setSmallIcon(Icon.createWithResource("android", android.R.drawable.ic_dialog_info))
                .setStyle(style)
                .addPerson(other)
                .build();
        final Parcel parcel = Parcel.obtain();
        try {
            posted.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return Notification.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    private static <T> T onImageThread(Callable<T> callable) throws Exception {
        final FutureTask<T> task = new FutureTask<>(callable);
        ImageThreads.execute("de.diamaneos.decode.client.tests", task);
        return task.get(30, TimeUnit.SECONDS);
    }

    private static List<String> texts(List<Notification.MessagingStyle.Message> messages) {
        final List<String> texts = new ArrayList<>();
        for (Notification.MessagingStyle.Message m : messages) {
            texts.add(String.valueOf(m.getText()));
        }
        return texts;
    }

    @Test
    public void receivedConversation_everyMessageKeepsItsText() throws Exception {
        final Notification original = received();
        final NotificationImageGate gate = NotificationImageGate.forNotification(mContext, mPackage,
                mContext.getUserId(), 64, 512, 512, new ImageBudget());
        gate.packageContext(mContext);
        final Notification copy = onImageThread(() -> gate.sanitizedCopy(original));

        final Notification.MessagingStyle style = (Notification.MessagingStyle)
                Notification.Builder.recoverBuilder(mContext, copy).getStyle();
        assertNotNull("the messaging style is gone", style);
        assertEquals(List.of("hello", "a picture"), texts(style.getMessages()));
        assertEquals(List.of("earlier"), texts(style.getHistoricMessages()));
        final Notification.MessagingStyle.Message hello = style.getMessages().get(0);
        assertEquals(1000, hello.getTimestamp());
        assertNotNull(hello.getSenderPerson());
        assertEquals("Other", String.valueOf(hello.getSenderPerson().getName()));
        final Icon sender = hello.getSenderPerson().getIcon();
        assertTrue("the sender's picture is still the app's bytes: " + sender,
                sender == null || sender.getType() == Icon.TYPE_BITMAP);
        // The image message keeps its link: SystemUI's inline image resolver makes it in the VM.
        final Notification.MessagingStyle.Message picture = style.getMessages().get(1);
        assertEquals("image/png", picture.getDataMimeType());
        assertEquals(PICTURE, picture.getDataUri());
        assertEquals("Chat", String.valueOf(style.getConversationTitle()));
        assertEquals("Me", String.valueOf(style.getUser().getName()));
        final ArrayList<Person> people = copy.extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST,
                Person.class);
        assertNotNull("the people list is gone", people);
        assertEquals(1, people.size());
        assertEquals("Other", String.valueOf(people.get(0).getName()));
    }
}
