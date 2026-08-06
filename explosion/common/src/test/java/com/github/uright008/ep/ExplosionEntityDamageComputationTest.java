package com.github.uright008.ep;

import java.util.Arrays;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExplosionEntityDamageComputationTest {

    @Test
    void computesDamageAndKnockbackFromPrimitiveSnapshot() {
        // exposurePreset=true: exposure comes from the snapshot, so the
        // worldView is never consulted — pass null to keep this a pure
        // primitive test (no Minecraft Blocks/registries bootstrap).
        ExplosionHelper.EntityDamageSnapshot snapshot = new ExplosionHelper.EntityDamageSnapshot(
                42, 2.0, 0.0, 0.0, 0.0,
                1.5, -0.5, -0.5, 2.5, 0.5, 0.5,
                true, 1.0F, 1.0F, true);

        ExplosionHelper.EntityDamageResult result = ExplosionHelper.computeEntityDamage(
                snapshot, 0.0, 0.0, 0.0, 4.0F, null);

        assertThat(result.entityId()).isEqualTo(42);
        assertThat(result.damage()).isEqualTo(11.5F);
        assertThat(result.kbX()).isEqualTo(0.5);
        assertThat(result.kbY()).isZero();
        assertThat(result.kbZ()).isZero();
    }

    @Test
    void workerRecordsContainNoLiveEntities() {
        assertThat(Arrays.stream(ExplosionHelper.EntityDamageSnapshot.class.getRecordComponents())
                .map(component -> component.getType()))
                .allMatch(type -> type != Entity.class
                        && type != Level.class
                        && type != BlockState.class
                        && type != VoxelShape.class);
        assertThat(Arrays.stream(ExplosionHelper.EntityDamageResult.class.getRecordComponents())
                .map(component -> component.getType()))
                .allMatch(type -> type != Entity.class
                        && type != Level.class
                        && type != BlockState.class
                        && type != VoxelShape.class);
    }
}
