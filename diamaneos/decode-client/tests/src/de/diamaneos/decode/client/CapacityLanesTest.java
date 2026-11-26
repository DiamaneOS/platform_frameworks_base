/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.*;
import java.util.Collections;
import java.util.function.IntSupplier;
import org.junit.Test;

public class CapacityLanesTest {
    @Test public void aProbeAndAnUnrelatedLaneCannotWakeALargeImage() {
        CapacityLanes lanes = new CapacityLanes();
        CapacityLanes.Lane large = lanes.refused("large", 800, 0);
        IntSupplier wake = CapacityLanes.snapshot(Collections.singletonList(large));
        assertFalse(lanes.completed("other", 1000));
        assertFalse(lanes.completed("large", 0));
        assertEquals(0, wake.getAsInt());
        assertTrue(lanes.completed("large", 800));
        assertEquals(1, wake.getAsInt());
    }
    @Test public void aLanesTimerDoesNotWakeAnotherLane() {
        CapacityLanes lanes = new CapacityLanes();
        CapacityLanes.Lane a = lanes.refused("a", 800, 0);
        CapacityLanes.Lane b = lanes.refused("b", 100, 500);
        assertTrue(lanes.tick(1000));
        assertEquals(1, a.version);
        assertEquals(0, b.version);
    }
    @Test public void allOverlappingTasksMustRecordTheirMissingImagesBeforeAWake() {
        CapacityLanes lanes = new CapacityLanes();
        CapacityLanes.Lane a = lanes.refused("a", 800, 0);
        lanes.taskStarted();
        lanes.taskStarted();
        assertFalse(lanes.completed("a", 800));
        assertFalse(lanes.tick(1000));
        assertFalse(lanes.taskEnded());
        assertEquals(0, a.version);
        assertTrue(lanes.taskEnded());
        assertEquals(1, a.version);
    }
    @Test public void oneCompletedDemandCannotBecomeAnEndlessWakeSource() {
        CapacityLanes lanes = new CapacityLanes();
        CapacityLanes.Lane lane = lanes.refused("a", 800, 0);
        assertTrue(lanes.completed("a", 800));
        assertFalse(lanes.completed("a", 800));
        assertEquals(1, lane.version);
        lanes.refused("a", 900, 2000);
        assertFalse(lanes.completed("a", 800));
        assertTrue(lanes.tick(3000));
        assertEquals(2, lane.version);
    }
    @Test public void liveLanesKeepTheirOwnTimerPastTheOldEvictionLimit() {
        CapacityLanes lanes = new CapacityLanes();
        java.util.List<CapacityLanes.Lane> live = new java.util.ArrayList<>();
        for (int i = 0; i < 600; i++) live.add(lanes.refused("lane" + i, 800, i));
        assertTrue(lanes.tick(1000));
        assertEquals(1, live.get(0).version);
        assertEquals(0, live.get(599).version);
    }
}
