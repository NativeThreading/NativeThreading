package com.github.uright008.ep;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the grouped-victim scratch in {@link ExplosionEntityStage}:
 * victims with identical (feet position, eye height, sampled exposure) share one
 * compute unit; group lookup keys on all five captured values and the table stops
 * growing at its capacity (overflow victims simply become own units).
 */
class ExplosionEntityStageGroupLookupTest {

    @BeforeEach
    void reset() {
        ExplosionEntityStage.clearStaticState();
    }

    @Test
    void identicalCapturedValuesShareOneUnit() {
        assertThat(ExplosionEntityStage.groupLookup(32.0, 8.75, -192.5, 8.75, 1.0F)).isEqualTo(-1);

        ExplosionEntityStage.groupStore(32.0, 8.75, -192.5, 8.75, 1.0F, 7);

        assertThat(ExplosionEntityStage.groupLookup(32.0, 8.75, -192.5, 8.75, 1.0F)).isEqualTo(0);
        assertThat(ExplosionEntityStage.groupUnit(0)).isEqualTo(7);
        assertThat(ExplosionEntityStage.groupCount()).isEqualTo(1);
    }

    @Test
    void anyCapturedValueDifferenceMisses() {
        ExplosionEntityStage.groupStore(32.0, 8.75, -192.5, 8.75, 0.75F, 3);

        assertThat(ExplosionEntityStage.groupLookup(32.1, 8.75, -192.5, 8.75, 0.75F))
                .as("different feetX").isEqualTo(-1);
        assertThat(ExplosionEntityStage.groupLookup(32.0, 8.76, -192.5, 8.75, 0.75F))
                .as("different feetY").isEqualTo(-1);
        assertThat(ExplosionEntityStage.groupLookup(32.0, 8.75, -192.4, 8.75, 0.75F))
                .as("different feetZ").isEqualTo(-1);
        assertThat(ExplosionEntityStage.groupLookup(32.0, 8.75, -192.5, 9.0, 0.75F))
                .as("different eyeY").isEqualTo(-1);
        assertThat(ExplosionEntityStage.groupLookup(32.0, 8.75, -192.5, 8.75, 0.5F))
                .as("different exposure").isEqualTo(-1);
    }

    @Test
    void distinctGroupsAreIndependent() {
        ExplosionEntityStage.groupStore(1.0, 2.0, 3.0, 2.0, 0.25F, 4);
        ExplosionEntityStage.groupStore(1.0, 2.0, 4.0, 2.0, 0.5F, 5);

        assertThat(ExplosionEntityStage.groupLookup(1.0, 2.0, 3.0, 2.0, 0.25F)).isEqualTo(0);
        assertThat(ExplosionEntityStage.groupLookup(1.0, 2.0, 4.0, 2.0, 0.5F)).isEqualTo(1);
        assertThat(ExplosionEntityStage.groupUnit(0)).isEqualTo(4);
        assertThat(ExplosionEntityStage.groupUnit(1)).isEqualTo(5);
    }

    @Test
    void tableStopsGrowingAtCapacity() {
        for (int i = 0; i < 20; i++) {
            ExplosionEntityStage.groupStore(i, 0.0, 0.0, 0.0, 1.0F, i);
        }
        assertThat(ExplosionEntityStage.groupCount()).isEqualTo(16);
        assertThat(ExplosionEntityStage.groupLookup(15.0, 0.0, 0.0, 0.0, 1.0F)).isEqualTo(15);
        assertThat(ExplosionEntityStage.groupLookup(16.0, 0.0, 0.0, 0.0, 1.0F)).isEqualTo(-1);
    }
}
