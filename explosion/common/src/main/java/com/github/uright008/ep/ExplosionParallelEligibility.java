package com.github.uright008.ep;

import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.EntityBasedExplosionDamageCalculator;

public final class ExplosionParallelEligibility {

    private ExplosionParallelEligibility() {}

    public enum Tier {
        A,
        B,
        C;

        public boolean allowsParallel() {
            return this != C;
        }
    }

    public static Tier resolveTier(Class<? extends ExplosionDamageCalculator> calculatorType) {
        if (calculatorType == ExplosionDamageCalculator.class) return Tier.A;
        if (calculatorType == EntityBasedExplosionDamageCalculator.class) return Tier.B;
        return Tier.C;
    }

    public static boolean allowsWorkerExecution(Class<? extends ExplosionDamageCalculator> calculatorType) {
        return resolveTier(calculatorType).allowsParallel();
    }
}
