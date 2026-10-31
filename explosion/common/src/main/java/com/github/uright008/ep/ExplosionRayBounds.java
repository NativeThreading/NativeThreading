package com.github.uright008.ep;

import net.minecraft.core.SectionPos;
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

    /** Half-extent of the box both the flat view and the entity blast box cover:
     *  the ray reach and the entity box (±(2r+1)), whichever is larger. */
    public static int bound(float radius) {
        return Math.max(blockReach(radius), Mth.ceil(radius * 2.0F + 1.0F));
    }

    /** Chunk rectangle that the flat view actually reads: exactly the sections the
     *  ±bound box intersects. Used when narrowGridFootprint is on (default); the
     *  padded-square path stays available as an opt-out. */
    public record SectionRect(int minX, int maxX, int minZ, int maxZ) {}

    public static SectionRect sectionRect(Vec3 center, float radius) {
        int bound = bound(radius);
        return new SectionRect(
                SectionPos.blockToSectionCoord(Mth.floor(center.x - bound)),
                SectionPos.blockToSectionCoord(Mth.floor(center.x + bound)),
                SectionPos.blockToSectionCoord(Mth.floor(center.z - bound)),
                SectionPos.blockToSectionCoord(Mth.floor(center.z + bound)));
    }

    /** Padded centred-square section range (ray reach and entity blast box,
     *  rounded up to sections plus one). Production grids use
     *  {@link #sectionRect} instead; this stays for the flat-view parity
     *  gametest, which deliberately exercises the padded square. */
    public static int sectionRange(float radius) {
        return Mth.ceil(bound(radius) / 16.0F) + 1;
    }

    public static ExplosionRayBounds forExplosion(Vec3 center, float radius) {
        // Entity blast box: vanilla hurtEntities scans ±(2r+1) around the
        // center. getSeenPercentFromFlatView samples the entity surface, and
        // sample rays must stay inside the flat view — otherwise cells beyond
        // it read as AIR and exposure is overestimated. Cover the entity box
        // so exposure DDA always resolves against real (captured) blocks.
        int bound = bound(radius);
        return new ExplosionRayBounds(
                Mth.floor(center.x - bound), Mth.floor(center.y - bound), Mth.floor(center.z - bound),
                Mth.floor(center.x + bound), Mth.floor(center.y + bound), Mth.floor(center.z + bound));
    }
}
