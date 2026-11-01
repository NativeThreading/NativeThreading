package com.github.uright008.nt.explosion;

import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.EntityBasedExplosionDamageCalculator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExplosionEligibilityTest {

    // ── Tier routing ──

    @Test
    void defaultCalculator_resolvesToTierA_staticWorldReadView() {
        assertThat(ExplosionEligibility.resolveTier(ExplosionDamageCalculator.class))
                .isEqualTo(ExplosionEligibility.Tier.A);
    }

    @Test
    void entityBasedCalculator_resolvesToTierB_cachedGeometryRebuild() {
        assertThat(ExplosionEligibility.resolveTier(EntityBasedExplosionDamageCalculator.class))
                .isEqualTo(ExplosionEligibility.Tier.B);
    }

    @Test
    void customCalculator_resolvesToTierC_vanillaFallback() {
        assertThat(ExplosionEligibility.resolveTier(CustomExplosionDamageCalculator.class))
                .isEqualTo(ExplosionEligibility.Tier.C);
    }

    @Test
    void tierA_allowsWorkersExecution() {
        assertThat(ExplosionEligibility.Tier.A.allowsWorkers()).isTrue();
    }

    @Test
    void tierB_allowsWorkersExecution() {
        assertThat(ExplosionEligibility.Tier.B.allowsWorkers()).isTrue();
    }

    @Test
    void tierC_doesNotAllowParallelExecution() {
        assertThat(ExplosionEligibility.Tier.C.allowsWorkers()).isFalse();
    }

    private static final class CustomExplosionDamageCalculator extends ExplosionDamageCalculator {}
}
