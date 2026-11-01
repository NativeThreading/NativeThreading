package com.github.uright008.nt.explosion;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the per-blast exposure dedup scratch in
 * {@link ExplosionEntityStage}: for a non-living entity whose collision context
 * is position-only, two victims sharing (AABB, feet Y, isDescending) must share
 * one sampled exposure.
 */
class ExplosionEntityStageExposureDedupTest {

    private static AABB box(double x, double y, double z) {
        return new AABB(x, y, z, x + 0.98, y + 0.98, z + 0.98);
    }

    @BeforeEach
    void reset() {
        ExplosionEntityStage.clearExposureDedup();
    }

    @Test
    void identicalContextSharesOneSampledValue() {
        AABB bb = box(32.0, 8.75, -192.5);
        assertThat(ExplosionEntityStage.exposureDedupLookup(bb, 8.75, false)).isEqualTo(-1);

        ExplosionEntityStage.exposureDedupStore(bb, 8.75, false, 0.375F);

        assertThat(ExplosionEntityStage.exposureDedupLookup(bb, 8.75, false)).isEqualTo(0);
        assertThat(ExplosionEntityStage.exposureDedupValue(0)).isEqualTo(0.375F);
    }

    @Test
    void anyKeyComponentDifferenceMisses() {
        AABB bb = box(32.0, 8.75, -192.5);
        ExplosionEntityStage.exposureDedupStore(bb, 8.75, false, 1.0F);

        assertThat(ExplosionEntityStage.exposureDedupLookup(box(32.0, 8.75, -191.5), 8.75, false))
                .as("different AABB").isEqualTo(-1);
        assertThat(ExplosionEntityStage.exposureDedupLookup(bb, 8.76, false))
                .as("different feet Y").isEqualTo(-1);
        assertThat(ExplosionEntityStage.exposureDedupLookup(bb, 8.75, true))
                .as("different isDescending").isEqualTo(-1);
    }

    @Test
    void distinctGroupsAreIndependent() {
        AABB a = box(1, 2, 3);
        AABB b = box(1, 2, 4);
        ExplosionEntityStage.exposureDedupStore(a, 2.0, false, 0.25F);
        ExplosionEntityStage.exposureDedupStore(b, 2.0, false, 0.75F);

        assertThat(ExplosionEntityStage.exposureDedupLookup(a, 2.0, false)).isEqualTo(0);
        assertThat(ExplosionEntityStage.exposureDedupLookup(b, 2.0, false)).isEqualTo(1);
        assertThat(ExplosionEntityStage.exposureDedupValue(0)).isEqualTo(0.25F);
        assertThat(ExplosionEntityStage.exposureDedupValue(1)).isEqualTo(0.75F);
    }

    @Test
    void scratchStopsGrowingAtCapacity() {
        AABB bb = box(5, 5, 5);
        for (int i = 0; i < 10; i++) {
            ExplosionEntityStage.exposureDedupStore(bb, i, false, 0.5F);
        }
        // The first eight slots exist; overflow groups are simply not cached.
        assertThat(ExplosionEntityStage.exposureDedupLookup(bb, 7.0, false)).isEqualTo(7);
        assertThat(ExplosionEntityStage.exposureDedupLookup(bb, 8.0, false)).isEqualTo(-1);
        assertThat(ExplosionEntityStage.exposureDedupLookup(bb, 9.0, false)).isEqualTo(-1);
    }
}
