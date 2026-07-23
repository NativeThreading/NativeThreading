package com.github.uright008.ep;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public record ExplosionRayBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    private static final float RAY_STEP = 0.3F;
    private static final float POWER_DECAY_PER_STEP = 0.22500001F;
    private static final float MAX_INITIAL_POWER_MULTIPLIER = 1.3F;

    public static ExplosionRayBounds forExplosion(Vec3 center, float radius) {
        int reach = Mth.ceil(Mth.ceil(radius * MAX_INITIAL_POWER_MULTIPLIER / POWER_DECAY_PER_STEP) * RAY_STEP);
        return new ExplosionRayBounds(
                Mth.floor(center.x - reach), Mth.floor(center.y - reach), Mth.floor(center.z - reach),
                Mth.floor(center.x + reach), Mth.floor(center.y + reach), Mth.floor(center.z + reach));
    }
}
