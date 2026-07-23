package com.github.uright008.ep;

import java.util.Arrays;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExplosionEntityDamageComputationTest {

    @Test
    void computesDamageAndKnockbackFromPrimitiveSnapshot() {
        ExplosionHelper.EntityDamageSnapshot snapshot = new ExplosionHelper.EntityDamageSnapshot(
                42, 10L, 11L, 2.0, 0.0, 0.0, 0.0,
                1.5, -0.5, -0.5, 2.5, 0.5, 0.5,
                true, 1.0F, 1.0F, 2.0F, null);

        ExplosionHelper.EntityDamageResult result = ExplosionHelper.computeEntityDamage(
                snapshot, 0.0, 0.0, 0.0, 4.0F);

        assertThat(result.entityId()).isEqualTo(42);
        assertThat(result.uuidMostSignificantBits()).isEqualTo(10L);
        assertThat(result.uuidLeastSignificantBits()).isEqualTo(11L);
        assertThat(result.damage()).isEqualTo(11.5F);
        assertThat(result.kbX()).isEqualTo(0.5);
        assertThat(result.kbY()).isZero();
        assertThat(result.kbZ()).isZero();
    }

    @Test
    void workerRecordsContainNoLiveEntities() {
        assertThat(Arrays.stream(ExplosionHelper.EntityDamageSnapshot.class.getRecordComponents())
                .map(component -> component.getType()))
                .allMatch(type -> type != Entity.class);
        assertThat(Arrays.stream(ExplosionHelper.EntityDamageResult.class.getRecordComponents())
                .map(component -> component.getType()))
                .allMatch(type -> type != Entity.class);
    }

    @Test
    void computesRayLookupExposureFromSnapshotPrimitivesAndDepthTable() {
        float[] openDepths = new float[ExplosionHelper.RAY_PARAMS.size()];
        Arrays.fill(openDepths, Float.MAX_VALUE);
        ExplosionHelper.EntityDamageSnapshot snapshot = new ExplosionHelper.EntityDamageSnapshot(
                42, 10L, 11L, 2.0, 0.0, 0.0, 0.0,
                1.5, -0.5, -0.5, 2.5, 0.5, 0.5,
                true, 1.0F, 0.0F, 2.0F, openDepths);

        float exposure = ExplosionHelper.getSeenPercentFast(snapshot, 0.0, 0.0, 0.0);

        assertThat(exposure).isEqualTo(1.0F);
    }
}
