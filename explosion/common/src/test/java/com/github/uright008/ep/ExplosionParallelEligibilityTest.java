package com.github.uright008.ep;

import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.EntityBasedExplosionDamageCalculator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExplosionParallelEligibilityTest {

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

    private static final class CustomExplosionDamageCalculator extends ExplosionDamageCalculator {}
}
