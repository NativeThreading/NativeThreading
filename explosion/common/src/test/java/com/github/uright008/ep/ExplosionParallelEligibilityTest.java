package com.github.uright008.ep;

import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.EntityBasedExplosionDamageCalculator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExplosionParallelEligibilityTest {

    // ── Baseline characterization: existing allowsWorkerExecution behavior ──

    @Test
    void defaultCalculator_allowsWorkerExecution() {
        assertThat(ExplosionParallelEligibility.allowsWorkerExecution(ExplosionDamageCalculator.class)).isTrue();
    }

    @Test
    void entityBasedCalculator_allowsWorkerExecution() {
        assertThat(ExplosionParallelEligibility.allowsWorkerExecution(EntityBasedExplosionDamageCalculator.class)).isTrue();
    }

    @Test
    void customCalculator_routesToVanillaExecution() {
        assertThat(ExplosionParallelEligibility.allowsWorkerExecution(CustomExplosionDamageCalculator.class)).isFalse();
    }

    // ── Tier routing ──

    @Test
    void defaultCalculator_resolvesToTierA_staticWorldReadView() {
        assertThat(ExplosionParallelEligibility.resolveTier(ExplosionDamageCalculator.class))
                .isEqualTo(ExplosionParallelEligibility.Tier.A);
    }

    @Test
    void entityBasedCalculator_resolvesToTierB_cachedGeometryRebuild() {
        assertThat(ExplosionParallelEligibility.resolveTier(EntityBasedExplosionDamageCalculator.class))
                .isEqualTo(ExplosionParallelEligibility.Tier.B);
    }

    @Test
    void customCalculator_resolvesToTierC_vanillaFallback() {
        assertThat(ExplosionParallelEligibility.resolveTier(CustomExplosionDamageCalculator.class))
                .isEqualTo(ExplosionParallelEligibility.Tier.C);
    }

    @Test
    void tierA_allowsParallelExecution() {
        assertThat(ExplosionParallelEligibility.Tier.A.allowsParallel()).isTrue();
    }

    @Test
    void tierB_allowsParallelExecution() {
        assertThat(ExplosionParallelEligibility.Tier.B.allowsParallel()).isTrue();
    }

    @Test
    void tierC_doesNotAllowParallelExecution() {
        assertThat(ExplosionParallelEligibility.Tier.C.allowsParallel()).isFalse();
    }

    private static final class CustomExplosionDamageCalculator extends ExplosionDamageCalculator {}
}
