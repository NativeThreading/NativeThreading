package com.github.uright008.benchmark.tnt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TntWindowHelperTest {
    @Test void percentilesAndPopulationVariance() {
        double[] input = {4, 1, 3, 2};
        var result = TntWindowHelper.statistics(input);
        assertEquals(2.5, result.get("mean_ms"));
        assertEquals(2.0, result.get("p50_ms"));
        assertEquals(4.0, result.get("p95_ms"));
        assertEquals(4.0, result.get("p99_ms"));
        assertEquals(4.0, result.get("max_ms"));
        assertEquals(Math.sqrt(1.25), (double) result.get("stddev_ms"), 1e-12);
        assertArrayEquals(new double[]{4, 1, 3, 2}, input);
    }

    @Test void constantWindow() {
        var result = TntWindowHelper.statistics(new double[]{50, 50});
        assertEquals(50.0, result.get("mean_ms"));
        assertEquals(0.0, result.get("stddev_ms"));
    }

    @Test void emptyWindowRejected() {
        assertThrows(IllegalArgumentException.class, () -> TntWindowHelper.statistics(new double[0]));
    }
}
