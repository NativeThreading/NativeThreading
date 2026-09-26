package com.github.uright008.benchmark;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AllayDropLedgerTest {
    private final AllayDropLedger ledger = new AllayDropLedger();
    private final UUID target = new UUID(0, 1);
    private final UUID source = new UUID(0, 2);

    @Test
    void duplicateEntityIdentityFailsWithoutReplacingOriginalDrop() {
        var original = new AllayDropLedger.Drop("warmup", 0, 10, 100);
        var duplicate = new AllayDropLedger.Drop("measurement", 7, 20, 200);
        ledger.add(target, original);

        assertThrows(IllegalStateException.class,
            () -> ledger.add(new UUID(0, 1), duplicate));

        assertEquals(List.of(original), ledger.pickup(target, 0));
        assertEquals("picked_up", original.status);
        assertEquals("remaining", duplicate.status);
    }

    @Test
    void fullMergePreservesFifoIdentityAndSourceCleanupCannotLoseTransferredDrops() {
        var first = new AllayDropLedger.Drop("warmup", 1, 10, 100);
        var second = new AllayDropLedger.Drop("measurement", 6, 20, 200);
        ledger.add(target, first);
        ledger.add(source, second);

        ledger.merge(target, source, 2);
        ledger.lost(source);
        ledger.lost(source);
        assertTrue(ledger.pickup(source, 0).isEmpty());
        assertEquals("remaining", first.status);
        assertEquals("remaining", second.status);

        var picked = ledger.pickup(target, 0);
        assertEquals(List.of(first, second), picked);
        assertSame(first, picked.get(0));
        assertSame(second, picked.get(1));
        assertEquals("warmup", first.phase);
        assertEquals(1, first.layer);
        assertEquals(10L, first.tick);
        assertEquals(100L, first.nanos);
        assertEquals("measurement", second.phase);
        assertEquals(6, second.layer);
        assertEquals(20L, second.tick);
        assertEquals(200L, second.nanos);

        ledger.lost(target);
        ledger.lost(target);
        assertEquals("picked_up", first.status);
        assertEquals("picked_up", second.status);
        assertTrue(ledger.pickup(target, 0).isEmpty());

        var replacement = new AllayDropLedger.Drop("measurement", 0, 30, 300);
        assertDoesNotThrow(() -> ledger.add(source, replacement));
        assertEquals(List.of(replacement), ledger.pickup(source, 0));
    }

    @Test
    void partialMergeAndPartialPickupConserveEachRecordInFifoOrder() {
        UUID thirdItem = new UUID(0, 3);
        UUID fourthItem = new UUID(0, 4);
        var first = new AllayDropLedger.Drop("warmup", 0, 1, 10);
        var second = new AllayDropLedger.Drop("warmup", 7, 2, 20);
        var third = new AllayDropLedger.Drop("measurement", 2, 3, 30);
        var fourth = new AllayDropLedger.Drop("measurement", 5, 4, 40);
        ledger.add(target, first);
        ledger.add(source, second);
        ledger.add(thirdItem, third);
        ledger.add(fourthItem, fourth);
        ledger.merge(source, thirdItem, 2);
        ledger.merge(source, fourthItem, 3);

        ledger.merge(target, source, 3);
        assertEquals(List.of(first, second), ledger.pickup(target, 1));
        assertEquals("picked_up", first.status);
        assertEquals("picked_up", second.status);
        assertEquals("remaining", third.status);
        assertEquals("remaining", fourth.status);

        assertEquals(List.of(fourth), ledger.pickup(source, 0));
        assertEquals(List.of(third), ledger.pickup(target, 0));
        for (var drop : List.of(first, second, third, fourth)) {
            assertEquals("picked_up", drop.status);
        }
        for (UUID item : List.of(target, source, thirdItem, fourthItem)) {
            ledger.lost(item);
            assertTrue(ledger.pickup(item, 0).isEmpty());
        }
        for (var drop : List.of(first, second, third, fourth)) {
            assertEquals("picked_up", drop.status);
        }
    }

    @Test
    void repeatedLostOnlyMarksUnpickedRemainder() {
        var picked = new AllayDropLedger.Drop("warmup", 0, 1, 10);
        var remaining = new AllayDropLedger.Drop("measurement", 7, 2, 20);
        ledger.add(target, picked);
        ledger.add(source, remaining);
        ledger.merge(target, source, 2);
        assertEquals(List.of(picked), ledger.pickup(target, 1));

        ledger.lost(source);
        assertEquals("remaining", remaining.status);
        ledger.lost(target);
        ledger.lost(target);
        assertEquals("picked_up", picked.status);
        assertEquals("lost", remaining.status);
        assertTrue(ledger.pickup(target, 0).isEmpty());
    }

    @Test
    void unchangedCountsDoNotTransferOrPickUpDrops() {
        var first = new AllayDropLedger.Drop("warmup", 0, 1, 10);
        var second = new AllayDropLedger.Drop("measurement", 1, 2, 20);
        ledger.add(target, first);
        ledger.add(source, second);

        ledger.merge(target, source, 1);
        assertTrue(ledger.pickup(target, 1).isEmpty());
        assertTrue(ledger.pickup(source, 1).isEmpty());
        assertEquals("remaining", first.status);
        assertEquals("remaining", second.status);
        assertEquals(List.of(first), ledger.pickup(target, 0));
        assertEquals(List.of(second), ledger.pickup(source, 0));
    }

    @Test
    void negativeOrExcessivePickupRemainingFailsWithoutConsumingDrops() {
        var drop = new AllayDropLedger.Drop("measurement", 0, 1, 10);
        ledger.add(target, drop);
        for (int remaining : new int[]{-1, Integer.MIN_VALUE, 2, Integer.MAX_VALUE}) {
            assertThrows(IllegalStateException.class, () -> ledger.pickup(target, remaining));
            assertEquals("remaining", drop.status);
        }
        assertEquals(List.of(drop), ledger.pickup(target, 0));
    }

    @Test
    void invalidMergeCountsIncludingOverflowFailWithoutMovingDrops() {
        var first = new AllayDropLedger.Drop("warmup", 0, 1, 10);
        var second = new AllayDropLedger.Drop("measurement", 1, 2, 20);
        ledger.add(target, first);
        ledger.add(source, second);
        for (int targetCount : new int[]{0, -1, Integer.MIN_VALUE, 3, Integer.MAX_VALUE}) {
            assertThrows(IllegalStateException.class, () -> ledger.merge(target, source, targetCount));
        }
        assertEquals(List.of(first), ledger.pickup(target, 0));
        assertEquals(List.of(second), ledger.pickup(source, 0));
    }

    @Test
    void mixingTrackedAndUntrackedItemsRejectsBothDirections() {
        var drop = new AllayDropLedger.Drop("measurement", 0, 1, 10);
        ledger.add(target, drop);
        assertThrows(IllegalStateException.class, () -> ledger.merge(target, source, 2));
        assertThrows(IllegalStateException.class, () -> ledger.merge(source, target, 2));
        assertEquals("remaining", drop.status);
        assertEquals(List.of(drop), ledger.pickup(target, 0));
    }

    @Test
    void trackedItemCannotMergeWithItself() {
        var drop = new AllayDropLedger.Drop("measurement", 0, 1, 10);
        ledger.add(target, drop);
        assertThrows(IllegalStateException.class, () -> ledger.merge(target, new UUID(0, 1), 2));
        assertEquals(List.of(drop), ledger.pickup(target, 0));
    }

    @Test
    void bothUntrackedMergeAndUntrackedCleanupAreNoOps() {
        assertDoesNotThrow(() -> ledger.merge(target, source, 2));
        assertDoesNotThrow(() -> ledger.lost(target));
        assertDoesNotThrow(() -> ledger.lost(target));
        assertTrue(ledger.pickup(target, 0).isEmpty());
        assertTrue(ledger.pickup(source, 0).isEmpty());

        var drop = new AllayDropLedger.Drop("measurement", 0, 1, 10);
        assertDoesNotThrow(() -> ledger.add(target, drop));
        assertEquals(List.of(drop), ledger.pickup(target, 0));
    }
}
