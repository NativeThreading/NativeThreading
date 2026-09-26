package com.github.uright008.benchmark;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AllayScenarioHelperTest {
    @Test
    void acceptsEntityCountsAtLeast64AndDivisibleByEight() {
        for (int count : new int[]{64, 128, 512}) {
            assertDoesNotThrow(() -> AllayScenarioHelper.validateEntities(count));
        }
    }

    @Test
    void rejectsTooFewOrIndivisibleEntityCounts() {
        for (int count : new int[]{0, 63, 65}) {
            assertThrows(IllegalArgumentException.class,
                () -> AllayScenarioHelper.validateEntities(count), "count=" + count);
        }
    }

    @Test
    void schedulesCeilingTickBatchesWithEveryLayerInsideItsRoom() {
        int[][] cases = {{1, 8}, {20, 8}, {21, 16}, {100, 40}, {101, 48}, {12000, 4800}};
        for (int[] testCase : cases) {
            var positions = AllayScenarioHelper.schedule(123456789L, "measurement", testCase[0]);
            assertEquals(testCase[1], positions.length, "ticks=" + testCase[0]);
            int[] layerCounts = new int[8];
            for (int i = 0; i < positions.length; i++) {
                var position = positions[i];
                assertEquals(i % 8, position.layer(), "one drop per layer in each batch");
                layerCounts[position.layer()]++;
                assertTrue(position.x() >= 2 && position.x() < 14, "x inside room");
                assertTrue(position.z() >= 2 && position.z() < 14, "z inside room");
                int floor = 64 + position.layer() * 8;
                assertTrue(position.y() >= floor + 2 && position.y() < floor + 6,
                    "y inside layer " + position.layer());
            }
            int[] expectedCounts = new int[8];
            Arrays.fill(expectedCounts, testCase[1] / 8);
            assertArrayEquals(expectedCounts, layerCounts);
        }
    }

    @Test
    void rejectsTicksOutsideSupportedRange() {
        for (int ticks : new int[]{Integer.MIN_VALUE, -1, 0, 12001, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                () -> AllayScenarioHelper.schedule(42L, "measurement", ticks), "ticks=" + ticks);
        }
    }

    @Test
    void scheduleRepeatsForSameSeedAndPhaseButChangesWithEither() {
        var expected = AllayScenarioHelper.schedule(42L, "measurement", 101);
        assertArrayEquals(expected, AllayScenarioHelper.schedule(42L, "measurement", 101));
        assertFalse(Arrays.equals(expected, AllayScenarioHelper.schedule(43L, "measurement", 101)));
        assertFalse(Arrays.equals(expected, AllayScenarioHelper.schedule(42L, "warmup", 101)));
    }

    @Test
    void emptyStatisticsAreZero() {
        double[] values = {};
        assertEquals(Map.of("mean", 0.0, "p50", 0.0, "p95", 0.0, "p99", 0.0, "max", 0.0),
            AllayScenarioHelper.statistics(values));
        assertArrayEquals(new double[0], values);
    }

    @Test
    void singletonStatisticsEqualTheOnlyValue() {
        double[] values = {7.25};
        assertEquals(Map.of("mean", 7.25, "p50", 7.25, "p95", 7.25, "p99", 7.25, "max", 7.25),
            AllayScenarioHelper.statistics(values));
        assertArrayEquals(new double[]{7.25}, values);
    }

    @Test
    void evenSampleUsesNearestRankRatherThanInterpolatedMedian() {
        double[] values = {9, 1, 5, 1};
        assertEquals(Map.of("mean", 4.0, "p50", 1.0, "p95", 9.0, "p99", 9.0, "max", 9.0),
            AllayScenarioHelper.statistics(values));
        assertArrayEquals(new double[]{9, 1, 5, 1}, values);
    }

    @Test
    void percentilesUseCeilingRanksAndDoNotSortInputInPlace() {
        double[] values = new double[101];
        for (int i = 0; i < values.length; i++) values[i] = 101 - i;
        double[] original = values.clone();
        assertEquals(Map.of("mean", 51.0, "p50", 51.0, "p95", 96.0, "p99", 100.0, "max", 101.0),
            AllayScenarioHelper.statistics(values));
        assertArrayEquals(original, values);
    }
}
