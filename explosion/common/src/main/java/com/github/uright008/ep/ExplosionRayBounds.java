package com.github.uright008.ep;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public record ExplosionRayBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    private static final float RAY_STEP = 0.3F;
    private static final float POWER_DECAY_PER_STEP = 0.22500001F;
    private static final float MAX_INITIAL_POWER_MULTIPLIER = 1.3F;

    /** Single source of truth for the block-space reach of an explosion: the
     *  farthest a power-decaying ray can march (float math, vanilla's
     *  {@code radius * (0.7 + random*0.6)} capped by the 1.3F multiplier). */
    public static int blockReach(float radius) {
        return Mth.ceil(Mth.ceil(radius * MAX_INITIAL_POWER_MULTIPLIER / POWER_DECAY_PER_STEP) * RAY_STEP);
    }

    /** Section range that must be loaded around the blast: the ray reach and
     *  the entity blast box (±(2r+1), which the exposure DDA samples), rounded
     *  up to 16-block sections plus one. Used by both the flat view and the
     *  ChunkGrid so they always agree. */
    public static int sectionRange(float radius) {
        int entityReach = Mth.ceil(radius * 2.0F + 1.0F);
        int bound = Math.max(blockReach(radius), entityReach);
        return Mth.ceil(bound / 16.0F) + 1;
    }

    public static ExplosionRayBounds forExplosion(Vec3 center, float radius) {
        // Entity blast box: vanilla hurtEntities scans ±(2r+1) around the
        // center. getSeenPercentFromFlatView samples the entity surface, and
        // sample rays must stay inside the flat view — otherwise cells beyond
        // it read as AIR and exposure is overestimated. Cover the entity box
        // so exposure DDA always resolves against real (captured) blocks.
        int entityReach = Mth.ceil(radius * 2.0F + 1.0F);
        int bound = Math.max(blockReach(radius), entityReach);
        return new ExplosionRayBounds(
                Mth.floor(center.x - bound), Mth.floor(center.y - bound), Mth.floor(center.z - bound),
                Mth.floor(center.x + bound), Mth.floor(center.y + bound), Mth.floor(center.z + bound));
    }
}
