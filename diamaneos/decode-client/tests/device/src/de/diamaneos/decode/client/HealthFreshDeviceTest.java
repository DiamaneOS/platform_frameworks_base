// Copyright (C) 2026 DiamaneOS
package de.diamaneos.decode.client;

import static org.junit.Assert.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

public class HealthFreshDeviceTest {
    @Test public void anOrdinaryCachedAnswerCannotClearAnExistingVmOutage() throws Exception {
        DecodeClient client = new DecodeClient(InstrumentationRegistry.getInstrumentation().getTargetContext());
        java.lang.reflect.Field down = DecodeClient.class.getDeclaredField("mDown");
        java.lang.reflect.Field watching = DecodeClient.class.getDeclaredField("mWatching");
        down.setAccessible(true);
        watching.setAccessible(true);
        down.setBoolean(client, true);
        watching.setBoolean(client, true);
        java.lang.reflect.Method answered = DecodeClient.class.getDeclaredMethod("answered");
        answered.setAccessible(true);
        answered.invoke(client);
        assertTrue("an ordinary response proves service liveness, not current guest health", down.getBoolean(client));
    }
}
