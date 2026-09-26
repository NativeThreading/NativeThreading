package com.github.uright008.benchmark;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BlockUpdateScenarioHelperTest {
    /** Adult cow hitbox width is 0.9, so its body extends 0.45 from the centre. */
    private static final double COW_HALF_WIDTH = 0.9 / 2.0;

    // ------------------------------------------------------------------
    // validate(entities, interval, change)
    // ------------------------------------------------------------------

    @Test
    void validateAcceptsAtLeast64CowsIncludingNonMultiplesOfEight() {
        for (int entities : new int[]{64, 65, 100, 127, 128, 256, 512}) {
            assertDoesNotThrow(() -> BlockUpdateScenarioHelper.validate(entities, 40, "collision"),
                "entities=" + entities);
        }
    }

    @Test
    void validateAcceptsEveryIntervalFromOneTo1200() {
        for (int interval : new int[]{1, 2, 21, 40, 599, 600, 1199, 1200}) {
            assertDoesNotThrow(() -> BlockUpdateScenarioHelper.validate(64, interval, "same-shape"),
                "interval=" + interval);
        }
    }

    @Test
    void validateAcceptsExactlyTheThreeSupportedChangeModes() {
        for (String change : new String[]{"collision", "same-shape", "none"}) {
            assertDoesNotThrow(() -> BlockUpdateScenarioHelper.validate(64, 40, change), change);
        }
    }

    @Test
    void validateRejectsTooFewCowsBadIntervalsAndUnknownChange() {
        for (int entities : new int[]{Integer.MIN_VALUE, 0, 1, 63}) {
            assertThrows(IllegalArgumentException.class,
                () -> BlockUpdateScenarioHelper.validate(entities, 40, "collision"), "entities=" + entities);
        }
        for (int interval : new int[]{Integer.MIN_VALUE, -1, 0, 1201, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                () -> BlockUpdateScenarioHelper.validate(64, interval, "collision"), "interval=" + interval);
        }
        for (String change : new String[]{"", "solid", "Collision", "collision ", "same_shape"}) {
            assertThrows(IllegalArgumentException.class,
                () -> BlockUpdateScenarioHelper.validate(64, 40, change), "change=[" + change + "]");
        }
    }

    // ------------------------------------------------------------------
    // eventAt(tick, ticks, interval) — 1-based ticks, drain the last 21
    // ------------------------------------------------------------------

    @Test
    void eventAtIsOneBasedAndNeverFiresDuringTheFinalDrainTicks() {
        int ticks = 600;
        assertFalse(BlockUpdateScenarioHelper.eventAt(0, ticks, 40), "tick 0 is not a tick");
        assertFalse(BlockUpdateScenarioHelper.eventAt(-5, ticks, 40));
        assertTrue(BlockUpdateScenarioHelper.eventAt(1, ticks, 1), "first tick may fire");
        assertTrue(BlockUpdateScenarioHelper.eventAt(ticks - 21, ticks, 1), "last eligible tick may fire");
        for (int interval : new int[]{1, 3, 40, 1200}) {
            for (int tick = ticks - 21 + 1; tick <= ticks; tick++) {
                assertFalse(BlockUpdateScenarioHelper.eventAt(tick, ticks, interval),
                    "interval=" + interval + " tick=" + tick);
            }
        }
    }

    @Test
    void defaultScheduleFiresFourteenEvents() {
        // 600 ticks / 40-tick interval, minus the 21-tick drain window.
        assertEquals(14, countEvents(600, 40));
    }

    @Test
    void intervalOneFiresOncePerEligibleTick() {
        // 100 ticks - 21 drained = 79 events.
        assertEquals(79, countEvents(100, 1));
    }

    @Test
    void eventCountEqualsDrainAdjustedFloorAcrossTickLengthsAndIntervals() {
        int[] tickLengths = {21, 22, 23, 40, 41, 99, 100, 101, 600, 601, 1200, 12000};
        int[] intervals = {1, 2, 3, 7, 21, 40, 199, 600, 1200};
        for (int ticks : tickLengths) {
            for (int interval : intervals) {
                int expected = Math.max(0, (ticks - BlockUpdateScenarioHelper.DRAIN_TICKS) / interval);
                assertEquals(expected, countEvents(ticks, interval),
                    "ticks=" + ticks + " interval=" + interval);
            }
        }
    }

    @Test
    void eventsSitOnTheSameIntervalGridWithRegularSpacing() {
        for (int interval : new int[]{1, 2, 3, 40, 1200}) {
            int ticks = 600;
            int previous = 0;
            for (int tick = 1; tick <= ticks; tick++) {
                if (!BlockUpdateScenarioHelper.eventAt(tick, ticks, interval)) continue;
                assertEquals(0, tick % interval, "tick=" + tick + " interval=" + interval);
                if (previous != 0) assertEquals(interval, tick - previous, "spacing");
                previous = tick;
            }
            assertTrue(previous <= ticks - BlockUpdateScenarioHelper.DRAIN_TICKS,
                "last event leaves the drain window interval=" + interval);
        }
    }

    @Test
    void boundaryAtLastEligibleTickFiresExactlyForPredeterminedIntervals() {
        for (int interval : new int[]{1, 2, 7, 40}) {
            int ticks = 21 + 3 * interval; // the drain boundary lands on a grid multiple
            assertTrue(BlockUpdateScenarioHelper.eventAt(ticks - 21, ticks, interval), "interval=" + interval);
            assertFalse(BlockUpdateScenarioHelper.eventAt(ticks - 20, ticks, interval), "interval=" + interval);
            assertEquals(3, countEvents(ticks, interval), "interval=" + interval);
        }
    }

    // ------------------------------------------------------------------
    // blocks() — 9 distinct cells well above the tallest cow
    // ------------------------------------------------------------------

    @Test
    void blocksAreExactlyNineDistinctCellsHighAboveCowHeight() {
        var blocks = BlockUpdateScenarioHelper.blocks();
        assertEquals(BlockUpdateScenarioHelper.BLOCKS_PER_EVENT, blocks.length);
        assertEquals(9, blocks.length);
        Set<String> unique = new HashSet<>();
        for (var pos : blocks) {
            assertEquals(83.0, pos.y(), "blocks sit at y=83");
            assertTrue(pos.y() > 81.4, "y=83 clears a 1.4-tall cow standing on a y=80 lane");
            assertTrue(Set.of(16.0, 32.0, 48.0).contains(pos.x()), "x=" + pos.x());
            assertTrue(Set.of(8.0, 16.0, 24.0).contains(pos.z()), "z=" + pos.z());
            assertTrue(unique.add(pos.x() + "," + pos.y() + "," + pos.z()), "duplicate " + pos);
        }
        assertEquals(9, unique.size(), "all block positions distinct");
    }

    // ------------------------------------------------------------------
    // spawnPositions(entities, seed)
    // ------------------------------------------------------------------

    @Test
    void spawnPositionsMatchEntityCountForEveryRequiredSize() {
        for (int entities : new int[]{64, 65, 128, 256, 512}) {
            assertEquals(entities, BlockUpdateScenarioHelper.spawnPositions(entities, 42L).length,
                "entities=" + entities);
        }
    }

    @Test
    void spawnPositionsKeepEveryCowBodyInsideTheArena() {
        for (int entities : new int[]{64, 65, 128, 256, 512}) {
            for (var pos : BlockUpdateScenarioHelper.spawnPositions(entities, 7L)) {
                assertEquals(80.0, pos.y(), "cow lane height");
                assertTrue(pos.x() - COW_HALF_WIDTH > 1.0, "west wall clearance x=" + pos.x());
                assertTrue(pos.x() + COW_HALF_WIDTH < 64.0, "east wall clearance x=" + pos.x());
                assertTrue(pos.z() - COW_HALF_WIDTH > 1.0, "north wall clearance z=" + pos.z());
                assertTrue(pos.z() + COW_HALF_WIDTH < 32.0, "south wall clearance z=" + pos.z());
            }
        }
    }

    @Test
    void spawnLayoutIsDeterministicPerSeedAndChangesWithSeed() {
        long[] seeds = {0L, 1L, 42L, -1L, 123456789L};
        for (long seed : seeds) {
            for (int entities : new int[]{64, 128, 512}) {
                var first = BlockUpdateScenarioHelper.spawnPositions(entities, seed);
                var second = BlockUpdateScenarioHelper.spawnPositions(entities, seed);
                assertArrayEquals(first, second, "seed=" + seed + " entities=" + entities);
                var other = BlockUpdateScenarioHelper.spawnPositions(entities, seed + 1);
                assertFalse(Arrays.equals(first, other),
                    "seed must change layout seed=" + seed + " entities=" + entities);
            }
        }
    }

    private static int countEvents(int ticks, int interval) {
        int count = 0;
        for (int tick = 1; tick <= ticks; tick++) {
            if (BlockUpdateScenarioHelper.eventAt(tick, ticks, interval)) count++;
        }
        return count;
    }
}
