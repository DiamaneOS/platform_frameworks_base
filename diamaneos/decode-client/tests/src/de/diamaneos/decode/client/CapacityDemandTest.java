/* Copyright (C) 2026 DiamaneOS */
package de.diamaneos.decode.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

public class CapacityDemandTest {
    @Test
    public void handoffDoesNotLoseOrReplayAnEvent() {
        AtomicInteger lane = new AtomicInteger();
        CapacityDemand<String> demand = new CapacityDemand<>();
        RetryTurns<String> rows = new RetryTurns<>(3);
        demand.missing("image", lane::get, 0);
        lane.incrementAndGet();
        rows.leftOut("row", 0, demand.capacityWakes(), demand.observed());
        assertEquals(Collections.singletonList("row"), rows.due(0));
        rows.leftOut("row", 0, demand.capacityWakes(), demand.observed());
        assertTrue(rows.due(1).isEmpty());
    }

    @Test
    public void everyMissingInputKeepsItsOwnLane() {
        AtomicInteger a = new AtomicInteger(), b = new AtomicInteger();
        CapacityDemand<String> demand = new CapacityDemand<>();
        demand.missing("a", a::get, 0);
        demand.missing("b", b::get, 0);
        IntSupplier wakes = demand.capacityWakes();
        assertEquals(0, wakes.getAsInt());
        b.incrementAndGet();
        assertEquals(1, wakes.getAsInt());
        assertEquals(1, wakes.getAsInt());
        a.incrementAndGet();
        assertEquals(2, wakes.getAsInt());
        demand.remove("b");
        b.incrementAndGet();
        assertEquals(2, wakes.getAsInt());
    }

    @Test
    public void ordinaryDemandEndsWithoutErasingCapacityDemand() {
        AtomicInteger lane = new AtomicInteger();
        CapacityDemand<String> demand = new CapacityDemand<>();
        demand.missing("capacity", lane::get, 0);
        demand.missing("ordinary", null, 0);
        assertTrue(demand.capacityWakes() == null);
        demand.remove("ordinary");
        assertEquals(0, demand.capacityWakes().getAsInt());
        lane.incrementAndGet();
        assertEquals(1, demand.capacityWakes().getAsInt());
        demand.retain(Collections.emptySet());
        assertTrue(demand.isEmpty());
    }

    @Test
    public void aNewAttemptReplacesTheInputsOldLane() {
        AtomicInteger old = new AtomicInteger(), current = new AtomicInteger();
        CapacityDemand<String> demand = new CapacityDemand<>();
        demand.missing("image", old::get, 0);
        demand.missing("image", current::get, 0);
        old.incrementAndGet();
        assertEquals(0, demand.capacityWakes().getAsInt());
        current.incrementAndGet();
        assertEquals(1, demand.capacityWakes().getAsInt());
    }
}
