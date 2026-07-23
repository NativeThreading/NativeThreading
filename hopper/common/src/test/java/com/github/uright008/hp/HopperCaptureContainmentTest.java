package com.github.uright008.hp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HopperCaptureContainmentTest {

    @Test
    void captureEach_skipsFailedCaptureAndContinues() {
        List<String> snapshots = HopperParallelHelper.captureEach(
                List.of("first", "broken", "later"),
                hopper -> {
                    if (hopper.equals("broken")) throw new IllegalStateException("capture failed");
                    return hopper + " snapshot";
                });

        assertThat(snapshots).containsExactly("first snapshot", "later snapshot");
    }
}
