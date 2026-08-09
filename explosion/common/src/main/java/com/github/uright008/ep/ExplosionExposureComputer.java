package com.github.uright008.ep;

import net.minecraft.world.level.block.state.BlockState;

/** Vanilla-exact exposure sampling over a flat view (worker domain): grid the
 *  entity's bounding box exactly like {@code ServerExplosion.getSeenPercent}
 *  and cast each sample point against the flat view via
 *  {@link ExplosionRayCast}. Pure function of its arguments. */
public final class ExplosionExposureComputer {

    private ExplosionExposureComputer() {}

    /** Sampled exposure for one captured entity, using the precomputed flat
     *  view. Bit-compatible with vanilla's getSeenPercent sampling. */
    static float getSeenPercentFromFlatView(ExplosionEntityDamageComputer.EntityDamageSnapshot snapshot,
                                            double centerX, double centerY, double centerZ,
                                            WorldReadView<BlockState> worldView) {
        double minX = snapshot.minX(), maxX = snapshot.maxX();
        double minY = snapshot.minY(), maxY = snapshot.maxY();
        double minZ = snapshot.minZ(), maxZ = snapshot.maxZ();
        // Vanilla sampling: step 1/(size*2+1) per axis (getSeenPercent's f=2.0).
        double samplingFactor = 2.0;
        double xs = 1.0 / ((maxX - minX) * samplingFactor + 1.0);
        double ys = 1.0 / ((maxY - minY) * samplingFactor + 1.0);
        double zs = 1.0 / ((maxZ - minZ) * samplingFactor + 1.0);
        double xOffset = (1.0 - Math.floor(1.0 / xs) * xs) / 2.0;
        double zOffset = (1.0 - Math.floor(1.0 / zs) * zs) / 2.0;
        if (xs < 0.0 || ys < 0.0 || zs < 0.0) return 0.0F;

        int hits = 0, count = 0;
        for (double xx = 0.0; xx <= 1.0; xx += xs) {
            for (double yy = 0.0; yy <= 1.0; yy += ys) {
                for (double zz = 0.0; zz <= 1.0; zz += zs) {
                    double sx = minX + (maxX - minX) * xx + xOffset;
                    double sy = minY + (maxY - minY) * yy;
                    double sz = minZ + (maxZ - minZ) * zz + zOffset;
                    if (!ExplosionRayCast.rayIntersectsBlockFlat(sx, sy, sz, centerX, centerY, centerZ, worldView)) hits++;
                    count++;
                }
            }
        }
        return (float) hits / count;
    }
}
