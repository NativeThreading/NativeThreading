package com.github.uright008.nt.explosion;

import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

/** Vanilla-exact exposure sampling over a flat view (worker domain): grid the
 *  entity's bounding box exactly like {@code ServerExplosion.getSeenPercent}
 *  and cast each sample point against the flat view via
 *  {@link ExplosionRayCast}. Pure function of its arguments. */
public final class ExplosionExposureComputer {

    /** Maximum number of flat-view cells the clear-region precheck may visit.
     *  Larger envelopes fall through to the exact per-ray walk, which is cheaper
     *  than scanning a big box (only large entities near the blast-box edge have
     *  one). */
    private static final long CLEAR_REGION_MAX_CELLS = 128L;

    private ExplosionExposureComputer() {}

    /** Clear-region precheck budget, reported by {@code /nt explosion stats}. */
    static long clearRegionMaxCells() {
        return CLEAR_REGION_MAX_CELLS;
    }

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
        if (isClearExposureRegion(minX, minY, minZ, maxX, maxY, maxZ,
                centerX, centerY, centerZ, worldView)) {
            ExplosionStats.recordClearRegionHit();
            return 1.0F;
        }

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

    /**
     * True when no ray sampled by vanilla's {@code getSeenPercent} for this
     * entity can hit a collision box, so vanilla's exposure is exactly
     * {@code hits/count == 1.0F}. The check is a conservative envelope around the
     * entity AABB, the sample offsets and the blast center:
     *
     * <ul>
     *   <li>Sample X/Z lie in {@code [aabb.min, aabb.max + offset]} with
     *       {@code 0 <= offset < 1} (vanilla's sampling grid), sample Y in
     *       {@code [aabb.minY, aabb.maxY]}.</li>
     *   <li>{@code BlockGetter.traverseBlocks} extends both ray endpoints outward
     *       by {@code 1e-7 * length} before walking cells, covered by the
     *       {@code CLEAR_MARGIN} margin.</li>
     * </ul>
     *
     * <p>All visited cells therefore live inside the envelope; a single non-empty
     * box table cell in it makes the function return false and the exact ray walk
     * runs as before. This is a pure worker-side constant-factor win: no
     * behaviour change (bit-identical {@code 1.0F} on the hit).</p>
     */
    static boolean isClearExposureRegion(double minX, double minY, double minZ,
                                         double maxX, double maxY, double maxZ,
                                         double centerX, double centerY, double centerZ,
                                         WorldReadView<BlockState> worldView) {
        if (!(worldView instanceof WorldReadViewImpl impl)) return false;
        double[][] shapeBoxes = impl.shapeBoxes();
        if (shapeBoxes == null) return false;
        // Margin covers the vanilla traverseBlocks endpoint backoff (1e-7 * length)
        // and any floating-point slack at exact cell boundaries.
        final double margin = 1.0E-5;
        int bx0 = Mth.floor(Math.min(minX, centerX) - margin);
        int bx1 = Mth.floor(Math.max(maxX + 1.0, centerX) + margin);
        int by0 = Mth.floor(Math.min(minY, centerY) - margin);
        int by1 = Mth.floor(Math.max(maxY, centerY) + margin);
        int bz0 = Mth.floor(Math.min(minZ, centerZ) - margin);
        int bz1 = Mth.floor(Math.max(maxZ + 1.0, centerZ) + margin);

        int minViewX = impl.minX(), maxViewX = impl.maxX();
        int minViewY = impl.minY(), maxViewY = impl.maxY();
        int minViewZ = impl.minZ(), maxViewZ = impl.maxZ();
        int cx0 = Math.max(bx0, minViewX), cx1 = Math.min(bx1, maxViewX);
        int cy0 = Math.max(by0, minViewY), cy1 = Math.min(by1, maxViewY);
        int cz0 = Math.max(bz0, minViewZ), cz1 = Math.min(bz1, maxViewZ);
        if (cx0 > cx1 || cy0 > cy1 || cz0 > cz1) return true; // envelope entirely outside the view

        long volume = (long) (cx1 - cx0 + 1) * (cy1 - cy0 + 1) * (cz1 - cz0 + 1);
        if (volume > CLEAR_REGION_MAX_CELLS) return false;

        int strideY = impl.strideY(), strideZ = impl.strideZ();
        for (int z = cz0; z <= cz1; z++) {
            for (int y = cy0; y <= cy1; y++) {
                int row = (y - minViewY) * strideY + (z - minViewZ) * strideZ;
                int start = row + (cx0 - minViewX);
                int end = row + (cx1 - minViewX);
                for (int index = start; index <= end; index++) {
                    if (shapeBoxes[index] != null) return false;
                }
            }
        }
        return true;
    }
}
