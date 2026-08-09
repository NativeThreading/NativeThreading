package com.github.uright008.ep;

import net.minecraft.world.level.block.state.BlockState;

/** Ray-vs-flat-view intersection DDA (worker domain): walks a flat view's raw
 *  arrays/box table exactly as vanilla's exposure clip would, plus the step
 *  budget for the block-stage rays. Pure functions of their arguments. */
public final class ExplosionRayCast {

    private ExplosionRayCast() {}

    /** Step budget for a given explosion radius: the number of 0.3-block steps
     *  needed to walk the region reach, plus one so the ray terminates on or
     *  past the boundary exactly as vanilla's power-decay loop would. */
    public static int rayMaxSteps(float radius) {
        int reach = (int) Math.ceil(Math.ceil(radius * 1.3F / 0.22500001F) * 0.3);
        return (int) Math.ceil(reach / 0.3) + 1;
    }

    static boolean rayIntersectsBlockFlatSlow(double fx, double fy, double fz,
                                              double tx, double ty, double tz,
                                              WorldReadView<BlockState> worldView) {
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

        while (true) {
            if (stepX > 0 ? x > endX : (stepX < 0 ? x < endX : false)) break;
            if (stepY > 0 ? y > endY : (stepY < 0 ? y < endY : false)) break;
            if (stepZ > 0 ? z > endZ : (stepZ < 0 ? z < endZ : false)) break;

            BlockState state = worldView.getBlockState(x, y, z);
            if (!state.isAir()) {
                net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(null, null);
                if (shape == net.minecraft.world.phys.shapes.Shapes.block()) {
                    if (rayAabbIntersectsFlat(fx, fy, fz, tx, ty, tz, x, y, z, x + 1.0, y + 1.0, z + 1.0))
                        return true;
                } else if (!shape.isEmpty()) {
                    java.util.List<net.minecraft.world.phys.AABB> aabbs = shape.toAabbs();
                    for (net.minecraft.world.phys.AABB bb : aabbs) {
                        if (rayAabbIntersectsFlat(fx, fy, fz, tx, ty, tz,
                                x + bb.minX, y + bb.minY, z + bb.minZ,
                                x + bb.maxX, y + bb.maxY, z + bb.maxZ))
                            return true;
                    }
                }
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

    static boolean rayIntersectsBlockFlat(double fx, double fy, double fz,
                                          double tx, double ty, double tz,
                                          WorldReadView<BlockState> worldView) {
        // Vanilla BlockGetter.traverseBlocks backoff, applied ONCE here and
        // passed to both implementations (they receive already-backoffed
        // coordinates and must not re-apply). Bounds-check with the backoffed
        // endpoints so an extended start cell cannot index out of the view.
        double[] b = backoffEndpoints(fx, fy, fz, tx, ty, tz);
        double bfx = b[0], bfy = b[1], bfz = b[2], btx = b[3], bty = b[4], btz = b[5];
        // Fast path only when every block the DDA will visit is inside the flat
        // view; otherwise fall back to the reference implementation.
        if (worldView instanceof WorldReadViewImpl impl
                && rayWithinBounds(impl, bfx, bfy, bfz, btx, bty, btz)) {
            return rayIntersectsBlockFlatFast(bfx, bfy, bfz, btx, bty, btz, impl);
        }
        return rayIntersectsBlockFlatSlow(bfx, bfy, bfz, btx, bty, btz, worldView);
    }

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
     *  {fx,fy,fz,tx,ty,tz} with that extension applied — shared by both DDA
     *  implementations so they visit the identical cell sequence. */
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

    static boolean rayIntersectsBlockFlatFast(double fx, double fy, double fz,
                                              double tx, double ty, double tz,
                                              WorldReadViewImpl worldView) {
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

        // Loop-invariant view geometry is captured once per ray; the DDA then
        // walks the raw array with incremental index updates.
        double[][] shapeBoxes = worldView.shapeBoxes();
        int minX = worldView.minX(), minY = worldView.minY(), minZ = worldView.minZ();
        int strideY = worldView.strideY(), strideZ = worldView.strideZ();
        int index = (x - minX) + (y - minY) * strideY + (z - minZ) * strideZ;
        int stepYIndex = stepY * strideY;
        int stepZIndex = stepZ * strideZ;

        // shapeBoxes encodes the full cell state: null = air (skip), non-null
        // = solid boxes to test. This avoids the BlockState dereference +
        // isAir() virtual call on every step (the array is L1-resident).
        // When shapeBoxes is absent (legacy view), fall back to per-step
        // BlockState checks.
        if (shapeBoxes != null) {
            while (true) {
                if (stepX > 0 ? x > endX : (stepX < 0 ? x < endX : false)) break;
                if (stepY > 0 ? y > endY : (stepY < 0 ? y < endY : false)) break;
                if (stepZ > 0 ? z > endZ : (stepZ < 0 ? z < endZ : false)) break;

                double[] boxes = shapeBoxes[index];
                if (boxes != null) {
                    for (int b = 0; b < boxes.length; b += 6) {
                        if (rayAabbIntersectsFlat(fx, fy, fz, tx, ty, tz,
                                x + boxes[b], y + boxes[b + 1], z + boxes[b + 2],
                                x + boxes[b + 3], y + boxes[b + 4], z + boxes[b + 5]))
                            return true;
                    }
                }

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

        while (true) {
            if (stepX > 0 ? x > endX : (stepX < 0 ? x < endX : false)) break;
            if (stepY > 0 ? y > endY : (stepY < 0 ? y < endY : false)) break;
            if (stepZ > 0 ? z > endZ : (stepZ < 0 ? z < endZ : false)) break;

            BlockState[] states = worldView.states();
            BlockState state = states[index];
            if (!state.isAir()) {
                net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(null, null);
                if (shape == net.minecraft.world.phys.shapes.Shapes.block()) {
                    if (rayAabbIntersectsFlat(fx, fy, fz, tx, ty, tz, x, y, z, x + 1.0, y + 1.0, z + 1.0))
                        return true;
                } else if (!shape.isEmpty()) {
                    net.minecraft.world.phys.AABB bb = shape.bounds();
                    if (rayAabbIntersectsFlat(fx, fy, fz, tx, ty, tz,
                            x + bb.minX, y + bb.minY, z + bb.minZ,
                            x + bb.maxX, y + bb.maxY, z + bb.maxZ))
                        return true;
                }
            }

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
