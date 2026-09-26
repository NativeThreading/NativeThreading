package com.github.uright008.benchmark.tnt;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

final class TntWindowHelper {
    static Map<String, Object> statistics(double[] samples) {
        if (samples.length == 0) throw new IllegalArgumentException("Empty tick window");
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        double mean = Arrays.stream(sorted).average().orElseThrow();
        double variance = Arrays.stream(sorted).map(x -> (x - mean) * (x - mean)).average().orElseThrow();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mean_ms", mean);
        result.put("stddev_ms", Math.sqrt(variance));
        result.put("p50_ms", sorted[(int) Math.ceil(sorted.length * .50) - 1]);
        result.put("p95_ms", sorted[(int) Math.ceil(sorted.length * .95) - 1]);
        result.put("p99_ms", sorted[(int) Math.ceil(sorted.length * .99) - 1]);
        result.put("max_ms", sorted[sorted.length - 1]);
        return result;
    }
}
