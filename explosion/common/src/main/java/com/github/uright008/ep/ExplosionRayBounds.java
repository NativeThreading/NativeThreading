package com.github.uright008.ep;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public record ExplosionRayBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    private static final float RAY_STEP = 0.3F;
    private static final float POWER_DECAY_PER_STEP = 0.22500001F;
    private static final float MAX_INITIAL_POWER_MULTIPLIER = 1.3F;

    public static ExplosionRayBounds forExplosion(Vec3 center, float radius) {
        // Ray reach: the farthest a power-decaying ray can march.
        int reach = Mth.ceil(Mth.ceil(radius * MAX_INITIAL_POWER_MULTIPLIER / POWER_DECAY_PER_STEP) * RAY_STEP);
        // Entity blast box: vanilla hurtEntities scans ±(2r+1) around the
        // center. getSeenPercentFromFlatView samples the entity surface, and
        // sample rays must stay inside the flat view — otherwise cells beyond
        // it read as AIR and exposure is overestimated. Cover the entity box
        // so exposure DDA always resolves against real (captured) blocks.
        int entityReach = Mth.ceil(radius * 2.0F + 1.0F);
        int bound = Math.max(reach, entityReach);
        return new ExplosionRayBounds(
                Mth.floor(center.x - bound), Mth.floor(center.y - bound), Mth.floor(center.z - bound),
                Mth.floor(center.x + bound), Mth.floor(center.y + bound), Mth.floor(center.z + bound));
    }
}
