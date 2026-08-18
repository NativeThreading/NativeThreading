package com.github.uright008.ep;

import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The Tier B fast path skips the serial main-thread capture for sources that
 *  inherit the base {@link Entity} getBlockExplosionResistance/shouldBlockExplode
 *  implementations (identity + always-true), because their per-cell result is
 *  bit-identical to the pure static Tier A path. Only entity classes that
 *  actually override the callbacks must keep the capture. */
class ExplosionBlockStageEntityCallbackTest {

    @Test
    void primedTnt_inheritsBaseCallbacks_skipsCapture() {
        // Regular TNT is an entity source (Tier B) but does not override the
        // explosion callbacks itself — only its inner portal calculator does,
        // and that path uses a custom calculator (Tier C). So a normal TNT
        // blast must take the pure parallel path, not the serial capture.
        assertThat(ExplosionBlockStage.overridesExplosionCallbacks(PrimedTnt.class))
                .isFalse();
    }

    @Test
    void minecartTnt_overridesCallbacks_keepsCapture() {
        // MinecartTNT checks level.getBlockState(pos.above()) for its rail
        // exemption — the deadlock case the capture exists for.
        assertThat(ExplosionBlockStage.overridesExplosionCallbacks(MinecartTNT.class))
                .isTrue();
    }

    @Test
    void baseInheritingSubclass_skipsCapture() {
        assertThat(ExplosionBlockStage.overridesExplosionCallbacks(BaseCallbackEntity.class))
                .isFalse();
    }

    @Test
    void shouldBlockExplodeOverride_keepsCapture() {
        assertThat(ExplosionBlockStage.overridesExplosionCallbacks(ExplodeDeciderEntity.class))
                .isTrue();
    }

    @Test
    void resistanceOverride_keepsCapture() {
        assertThat(ExplosionBlockStage.overridesExplosionCallbacks(ResistanceEntity.class))
                .isTrue();
    }

    /** Inherits the base Entity callbacks — only ever referenced as a class. */
    static class BaseCallbackEntity extends Entity {
        BaseCallbackEntity(EntityType<?> type, Level level) {
            super(type, level);
        }

        @Override
        protected void defineSynchedData(SynchedEntityData.Builder builder) {}

        @Override
        public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
            return false;
        }

        @Override
        protected void readAdditionalSaveData(ValueInput input) {}

        @Override
        protected void addAdditionalSaveData(ValueOutput output) {}
    }

    static class ExplodeDeciderEntity extends BaseCallbackEntity {
        ExplodeDeciderEntity(EntityType<?> type, Level level) {
            super(type, level);
        }

        @Override
        public boolean shouldBlockExplode(Explosion explosion, BlockGetter level,
                                          BlockPos pos, BlockState state, float power) {
            return super.shouldBlockExplode(explosion, level, pos, state, power)
                    && state.getFluidState().isEmpty();
        }
    }

    static class ResistanceEntity extends BaseCallbackEntity {
        ResistanceEntity(EntityType<?> type, Level level) {
            super(type, level);
        }

        @Override
        public float getBlockExplosionResistance(Explosion explosion, BlockGetter level,
                                                 BlockPos pos, BlockState block,
                                                 FluidState fluid, float resistance) {
            return Math.min(0.8F, resistance);
        }
    }
}
