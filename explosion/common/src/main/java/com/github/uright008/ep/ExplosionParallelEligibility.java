package com.github.uright008.ep;

import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.EntityBasedExplosionDamageCalculator;

public final class ExplosionParallelEligibility {

    private ExplosionParallelEligibility() {}

    public static boolean allowsWorkerExecution(Class<? extends ExplosionDamageCalculator> calculatorType) {
        return calculatorType == ExplosionDamageCalculator.class
                || calculatorType == EntityBasedExplosionDamageCalculator.class;
    }
}
