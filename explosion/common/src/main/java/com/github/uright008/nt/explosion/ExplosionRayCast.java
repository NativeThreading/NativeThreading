package com.github.uright008.nt.explosion;

import net.minecraft.world.level.block.state.BlockState;

/** Ray-vs-flat-view intersection DDA (worker domain): walks a flat view's
 *  <em>precomputed</em> box table exactly as vanilla's exposure clip would,
 *  plus the step budget for the block-stage rays. Pure functions of their
 *  arguments — the box table is resolved on the main thread against the live
 *  level and the real cell position (see {@link ExplosionShapeBoxes}), so the
 *  worker never calls a level or block-shape API. */
public final class ExplosionRayCast {

    private ExplosionRayCast() {}

    /** Step budget for a given explosion radius: the number of 0.3-block steps
     *  needed to walk the region reach, plus one so the ray terminates on or
     *  past the boundary exactly as vanilla's power-decay loop would. */
    public static int rayMaxSteps(float radius) {
        int reach = ExplosionRayBounds.blockReach(radius);
        return (int) Math.ceil(reach / 0.3) + 1;
    }

    /** Production entry: applies vanilla's {@code BlockGetter.traverseBlocks}
     *  backoff once, then walks the precomputed box table. The view's shape
     *  snapshot is mandatory — workers never resolve block shapes themselves. */
    static boolean rayIntersectsBlockFlat(double fx, double fy, double fz,
                                          double tx, double ty, double tz,
                                          WorldReadView<BlockState> worldView) {
        double[] b = backoffEndpoints(fx, fy, fz, tx, ty, tz);
        if (!(worldView instanceof WorldReadViewImpl impl)) {
            throw new IllegalArgumentException(
                    "flat-view ray DDA requires a boxed WorldReadViewImpl, got " + worldView.getClass());
        }
        return rayIntersectsBlockFlatBoxed(b[0], b[1], b[2], b[3], b[4], b[5], impl);
    }

    /** Boxed DDA over the flat view. Cells whose box slot is null are air and
     *  are skipped; cells outside the view read as air, matching
     *  {@link WorldReadViewImpl#getBlockState}. Fully in-view rays take the
     *  incremental-index loop; rays starting or ending outside take the
     *  bounds-checked loop (large entities near the blast-box edge). */
    static boolean rayIntersectsBlockFlatBoxed(double fx, double fy, double fz,
                                               double tx, double ty, double tz,
                                               WorldReadViewImpl worldView) {
        double[][] shapeBoxes = worldView.shapeBoxes();
        if (shapeBoxes == null) {
            throw new IllegalArgumentException("flat-view ray DDA requires a precomputed box table");
        }
        double dx = tx - fx, dy = ty - fy, dz = tz - fz;
        double lenSq = dx * dx + dy * dy + dz * dz;
        if (lenSq < 1.0E-7) return false;

        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);
        double tDeltaX = stepX != 0 ? stepX / dx : Double.MAX_VALUE;
        double tDeltaY = stepY != 0 ? stepY / dy : Double.MAX_VALUE;
        double tDeltaZ = stepZ != 0 ? stepZ / dz : Double.MAX_VALUE;
        double tMaxX = tDeltaX * (stepX > 0 ? 1.0 - net.minecraft.util.Mth.frac(fx) : net.minecraft.util.Mth.frac(fx));
        double tMaxY = tDeltaY * (stepY > 0 ? 1.0 - net.minecraft.util.Mth.frac(fy) : net.minecraft.util.Mth.frac(fy));
        double tMaxZ = tDeltaZ * (stepZ > 0 ? 1.0 - net.minecraft.util.Mth.frac(fz) : net.minecraft.util.Mth.frac(fz));
        int x = net.minecraft.util.Mth.floor(fx), y = net.minecraft.util.Mth.floor(fy), z = net.minecraft.util.Mth.floor(fz);
        int endX = net.minecraft.util.Mth.floor(tx), endY = net.minecraft.util.Mth.floor(ty), endZ = net.minecraft.util.Mth.floor(tz);

        int minX = worldView.minX(), minY = worldView.minY(), minZ = worldView.minZ();
        int maxX = worldView.maxX(), maxY = worldView.maxY(), maxZ = worldView.maxZ();
        int strideY = worldView.strideY(), strideZ = worldView.strideZ();

        if (rayWithinBounds(worldView, fx, fy, fz, tx, ty, tz)) {
            // Hot path: every visited cell is inside the view, so the flat
            // index can be advanced incrementally with no bounds checks.
            int index = (x - minX) + (y - minY) * strideY + (z - minZ) * strideZ;
            int stepYIndex = stepY * strideY;
            int stepZIndex = stepZ * strideZ;
            while (true) {
                if (stepX > 0 ? x > endX : (stepX < 0 ? x < endX : false)) break;
                if (stepY > 0 ? y > endY : (stepY < 0 ? y < endY : false)) break;
                if (stepZ > 0 ? z > endZ : (stepZ < 0 ? z < endZ : false)) break;

                double[] boxes = shapeBoxes[index];
                if (boxes != null && rayHitsBoxes(fx, fy, fz, tx, ty, tz, x, y, z, boxes)) return true;

                if (tMaxX < tMaxY) {
                    if (tMaxX < tMaxZ) { x += stepX; index += stepX; tMaxX += tDeltaX; }
                    else                { z += stepZ; index += stepZIndex; tMaxZ += tDeltaZ; }
                } else {
                    if (tMaxY < tMaxZ) { y += stepY; index += stepYIndex; tMaxY += tDeltaY; }
                    else                { z += stepZ; index += stepZIndex; tMaxZ += tDeltaZ; }
                }
            }
            return false;
        }

        // Cold path: cells beyond the view read as air (WorldReadViewImpl's
        // out-of-bounds contract), in-view cells read the box table.
        while (true) {
            if (stepX > 0 ? x > endX : (stepX < 0 ? x < endX : false)) break;
            if (stepY > 0 ? y > endY : (stepY < 0 ? y < endY : false)) break;
            if (stepZ > 0 ? z > endZ : (stepZ < 0 ? z < endZ : false)) break;

            if (x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ) {
                double[] boxes = shapeBoxes[(x - minX) + (y - minY) * strideY + (z - minZ) * strideZ];
                if (boxes != null && rayHitsBoxes(fx, fy, fz, tx, ty, tz, x, y, z, boxes)) return true;
            }

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) { x += stepX; tMaxX += tDeltaX; }
                else                { z += stepZ; tMaxZ += tDeltaZ; }
            } else {
                if (tMaxY < tMaxZ) { y += stepY; tMaxY += tDeltaY; }
                else                { z += stepZ; tMaxZ += tDeltaZ; }
            }
        }
        return false;
    }

    /** True when every cell the DDA can visit lies inside the flat view. */
    static boolean rayWithinBounds(WorldReadViewImpl worldView,
                                   double fx, double fy, double fz,
                                   double tx, double ty, double tz) {
        return blockSpanWithinBounds(worldView.minX(), worldView.maxX(), fx, tx)
                && blockSpanWithinBounds(worldView.minY(), worldView.maxY(), fy, ty)
                && blockSpanWithinBounds(worldView.minZ(), worldView.maxZ(), fz, tz);
    }

    /** Vanilla BlockGetter.traverseBlocks extends both endpoints OUTWARD by
     *  1.0E-7×length before walking cells: {@code Mth.lerp(-1.0E-7, to, from)}
     *  = from + 1e-7·(from−to) and {@code lerp(-1.0E-7, from, to)} = to +
     *  1e-7·(to−from). So a sample point or center sitting exactly on an
     *  integer boundary is pushed just past it, into the next cell. Returns
     *  {fx,fy,fz,tx,ty,tz} with that extension applied. */
    static double[] backoffEndpoints(double fx, double fy, double fz,
                                     double tx, double ty, double tz) {
        double bx = (fx - tx) * 1.0E-7;
        double by = (fy - ty) * 1.0E-7;
        double bz = (fz - tz) * 1.0E-7;
        return new double[]{fx + bx, fy + by, fz + bz, tx - bx, ty - by, tz - bz};
    }

    private static boolean blockSpanWithinBounds(int min, int max, double from, double to) {
        int lo = net.minecraft.util.Mth.floor(from);
        int hi = net.minecraft.util.Mth.floor(to);
        if (lo > hi) { int t = lo; lo = hi; hi = t; }
        return lo >= min && hi <= max;
    }

    /** Exact per-box test for the cell at (x, y, z). */
    private static boolean rayHitsBoxes(double fx, double fy, double fz,
                                        double tx, double ty, double tz,
                                        int x, int y, int z, double[] boxes) {
        if (boxes.length == 6) {
            return rayAabbIntersectsFlat(fx, fy, fz, tx, ty, tz,
                    x + boxes[0], y + boxes[1], z + boxes[2],
                    x + boxes[3], y + boxes[4], z + boxes[5]);
        }
        for (int b = 0; b < boxes.length; b += 6) {
            if (rayAabbIntersectsFlat(fx, fy, fz, tx, ty, tz,
                    x + boxes[b], y + boxes[b + 1], z + boxes[b + 2],
                    x + boxes[b + 3], y + boxes[b + 4], z + boxes[b + 5]))
                return true;
        }
        return false;
    }

    private static boolean rayAabbIntersectsFlat(double fx, double fy, double fz,
                                                 double tx, double ty, double tz,
                                                 double minX, double minY, double minZ,
                                                 double maxX, double maxY, double maxZ) {
        double dirX = tx - fx, dirY = ty - fy, dirZ = tz - fz;
        double min = 0.0, max = 1.0;
        if (dirX == 0) { if (fx < minX || fx > maxX) return false; }
        else {
            double n = (minX - fx) / dirX, f = (maxX - fx) / dirX;
            if (n > f) { double t = n; n = f; f = t; }
            if (n > min) min = n; if (f < max) max = f;
            if (min > max) return false;
        }
        if (dirY == 0) { if (fy < minY || fy > maxY) return false; }
        else {
            double n = (minY - fy) / dirY, f = (maxY - fy) / dirY;
            if (n > f) { double t = n; n = f; f = t; }
            if (n > min) min = n; if (f < max) max = f;
            if (min > max) return false;
        }
        if (dirZ == 0) { if (fz < minZ || fz > maxZ) return false; }
        else {
            double n = (minZ - fz) / dirZ, f = (maxZ - fz) / dirZ;
            if (n > f) { double t = n; n = f; f = t; }
            if (n > min) min = n; if (f < max) max = f;
            if (min > max) return false;
        }
        return true;
    }
}
