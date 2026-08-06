package com.github.uright008.ep;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class ExplosionHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExplosionHelper.class);

    private ExplosionHelper() {}

    public record RayParam(double xd, double yd, double zd,
                           double stepX, double stepY, double stepZ) {}
    public record EntityDamageSnapshot(
            int entityId,
            double feetX,
            double feetY,
            double feetZ,
            double eyeY,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            boolean shouldDamage,
            float knockbackMultiplier,
            float exposure,
            boolean exposurePreset,
            double knockbackResistance) {
    }

    public record EntityDamageResult(
            int entityId,
            float damage,
            double kbX,
            double kbY,
            double kbZ) {
        public Vec3 makeKnockback() { return new Vec3(kbX, kbY, kbZ); }
    }

    public static EntityDamageResult computeEntityDamage(
            EntityDamageSnapshot snapshot,
            double centerX,
            double centerY,
            double centerZ,
            float doubleRadius,
            WorldReadView<net.minecraft.world.level.block.state.BlockState> worldView) {
        float exposure;
        if (snapshot.exposurePreset) {
            exposure = snapshot.exposure;
        } else if (snapshot.shouldDamage || snapshot.knockbackMultiplier != 0.0F) {
            exposure = getSeenPercentFromFlatView(snapshot, centerX, centerY, centerZ, worldView);
        } else {
            exposure = 0.0F;
        }
        return computeEntityDamage(snapshot, centerX, centerY, centerZ, doubleRadius, exposure);
    }

    private static EntityDamageResult computeEntityDamage(
            EntityDamageSnapshot snapshot,
            double centerX,
            double centerY,
            double centerZ,
            float doubleRadius,
            float exposure) {
        double dx = snapshot.feetX - centerX;
        double dy = snapshot.feetY - centerY;
        double dz = snapshot.feetZ - centerZ;
        double distanceRatio = Math.sqrt(dx * dx + dy * dy + dz * dz) / doubleRadius;
        // Vanilla: knockbackPower = (1.0 - dist) * exposure * knockbackMultiplier
        // * (1.0 - knockbackResistance) in one product. Resistance is captured
        // into the snapshot on the main thread so the worker computes the exact
        // same product order.
        double power = (1.0 - distanceRatio) * exposure * snapshot.knockbackMultiplier
                * (1.0 - snapshot.knockbackResistance);
        double knockbackX = snapshot.feetX - centerX;
        double knockbackY = snapshot.eyeY - centerY;
        double knockbackZ = snapshot.feetZ - centerZ;
        double knockbackLength = Math.sqrt(knockbackX * knockbackX
                + knockbackY * knockbackY + knockbackZ * knockbackZ);
        if (knockbackLength >= 1.0E-5F) {
            // Vanilla: direction.normalize().scale(power) = (component/len)*power.
            // Divide first, then multiply — matches vanilla's double rounding.
            knockbackX = (knockbackX / knockbackLength) * power;
            knockbackY = (knockbackY / knockbackLength) * power;
            knockbackZ = (knockbackZ / knockbackLength) * power;
        } else {
            knockbackX = 0.0;
            knockbackY = 0.0;
            knockbackZ = 0.0;
        }
        float damage = snapshot.shouldDamage
                ? vanillaDamage(doubleRadius, distanceRatio, exposure)
                : 0.0F;
        return new EntityDamageResult(snapshot.entityId,
                damage, knockbackX, knockbackY, knockbackZ);
    }

    /** True if the flat view contains scaffolding, powder snow, or a liquid —
     *  blocks whose collision shape depends on the querying entity context.
     *  Scaffolding/powder-snow vary their solid shape; LiquidBlock returns a
     *  non-empty fluid-collision shape for a living entity context but empty
     *  for {@code (null, null)}, so a liquid would let exposure rays pass that
     *  vanilla clip would stop. When any is present, exposure must be computed
     *  with the real entity context (vanilla-exact). */
    public static boolean hasEntityContextBlocks(WorldReadView<net.minecraft.world.level.block.state.BlockState> worldView) {
        if (!(worldView instanceof WorldReadViewImpl impl)) return false;
        BlockState[] states = impl.states();
        for (BlockState state : states) {
            Block block = state.getBlock();
            if (block instanceof ScaffoldingBlock || block instanceof PowderSnowBlock
                    || block instanceof net.minecraft.world.level.block.LiquidBlock) return true;
        }
        return false;
    }

    /** Flattens each cell's collision shape into axis-aligned boxes (6 doubles per
     *  box, relative to the cell origin). Air cells yield null; full-block cells
     *  yield the unit box {0,0,0,1,1,1}; partial shapes yield their exact box
     *  decomposition. The worker DDA then tests boxes directly and never touches
     *  the BlockState object (no isAir()/getCollisionShape dereference) — the
     *  null vs non-null test IS the air test. Precomputed on the main thread. */
    public static double[][] flattenShapeBoxes(
            BlockState[] states, VoxelShape[] shapes, int gridSize) {
        return flattenShapeBoxesReused(states, shapes, gridSize, null);
    }

    /** {@link #flattenShapeBoxes} with a reusable outer array. The outer
     *  {@code double[][]} (one slot per cell) is recycled across explosions
     *  via {@code cache}; per-box {@code double[]} payloads are fresh because
     *  shape sets vary. Cells that were non-null last time but are air now
     *  must be explicitly cleared — a stale box table would be read by the
     *  entity-exposure DDA. */
    public static double[][] flattenShapeBoxesReused(
            BlockState[] states, VoxelShape[] shapes, int gridSize,
            java.util.concurrent.atomic.AtomicReference<double[][]> cache) {
        double[][] boxes = cache != null ? cache.getAndSet(null) : null;
        if (boxes == null || boxes.length < gridSize) boxes = new double[gridSize][];
        double[] fullBox = new double[] {0.0, 0.0, 0.0, 1.0, 1.0, 1.0};
        for (int i = 0; i < gridSize; i++) {
            VoxelShape shape = shapes != null ? shapes[i] : null;
            if (shape == null) {
                boxes[i] = null;
                continue;
            }
            if (shape == net.minecraft.world.phys.shapes.Shapes.block()) {
                boxes[i] = fullBox;
                continue;
            }
            java.util.List<net.minecraft.world.phys.AABB> aabbs = shape.toAabbs();
            if (aabbs.size() == 1) {
                net.minecraft.world.phys.AABB bb = aabbs.get(0);
                boxes[i] = new double[] {
                        bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ};
            } else {
                double[] packed = new double[aabbs.size() * 6];
                for (int b = 0; b < aabbs.size(); b++) {
                    net.minecraft.world.phys.AABB bb = aabbs.get(b);
                    int o = b * 6;
                    packed[o] = bb.minX;
                    packed[o + 1] = bb.minY;
                    packed[o + 2] = bb.minZ;
                    packed[o + 3] = bb.maxX;
                    packed[o + 4] = bb.maxY;
                    packed[o + 5] = bb.maxZ;
                }
                boxes[i] = packed;
            }
        }
        return boxes;
    }

    /** Vanilla-exact exposure: {@link net.minecraft.world.level.ServerExplosion#getSeenPercent}
     *  with the real entity context (scaffolding isAbove/isDescending, powder-snow
     *  fallDistance/boots are all resolved from the entity). Main-thread only —
     *  touches the entity, never run on workers. */
    public static float computeContextAwareExposure(
            Entity entity, double centerX, double centerY, double centerZ) {
        return net.minecraft.world.level.ServerExplosion.getSeenPercent(
                new Vec3(centerX, centerY, centerZ), entity);
    }

    private static float getSeenPercentFromFlatView(EntityDamageSnapshot snapshot,
                                                    double centerX, double centerY, double centerZ,
                                                    WorldReadView<net.minecraft.world.level.block.state.BlockState> worldView) {
        double minX = snapshot.minX, maxX = snapshot.maxX;
        double minY = snapshot.minY, maxY = snapshot.maxY;
        double minZ = snapshot.minZ, maxZ = snapshot.maxZ;
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
                    if (!rayIntersectsBlockFlat(sx, sy, sz, centerX, centerY, centerZ, worldView)) hits++;
                    count++;
                }
            }
        }
        return (float) hits / count;
    }

    static boolean rayIntersectsBlockFlatSlow(double fx, double fy, double fz,
                                              double tx, double ty, double tz,
                                              WorldReadView<net.minecraft.world.level.block.state.BlockState> worldView) {
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

            net.minecraft.world.level.block.state.BlockState state = worldView.getBlockState(x, y, z);
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
                                          WorldReadView<net.minecraft.world.level.block.state.BlockState> worldView) {
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
            net.minecraft.world.level.block.state.BlockState state = states[index];
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

    private static float vanillaDamage(float doubleRadius, double distanceRatio, float exposure) {
        double power = (1.0 - distanceRatio) * exposure;
        return (float) ((power * power + power) / 2.0 * 7.0 * doubleRadius + 1.0);
    }

    public static Vec3 knockback(double x, double y, double z, double power) {
        double length = Math.sqrt(x * x + y * y + z * z);
        return length < 1.0E-5F ? Vec3.ZERO : new Vec3(x / length * power, y / length * power, z / length * power);
    }


    /** Step budget for a given explosion radius: the number of 0.3-block steps
     *  needed to walk the region reach ({@code ceil(radius*1.3/0.225)*0.3}
     *  blocks), plus one so the ray terminates on or past the boundary exactly
     *  as vanilla's power-decay loop would. */
    public static int rayMaxSteps(float radius) {
        int reach = (int) Math.ceil(Math.ceil(radius * 1.3F / 0.22500001F) * 0.3);
        return (int) Math.ceil(reach / 0.3) + 1;
    }

    public static final List<RayParam> RAY_PARAMS = generateRayParams();

    // ── Ray generation ───────────────────────────

    private static List<RayParam> generateRayParams() {
        List<RayParam> params = new ArrayList<>();
        for (int xx = 0; xx < 16; xx++) {
            for (int yy = 0; yy < 16; yy++) {
                for (int zz = 0; zz < 16; zz++) {
                    if (xx == 0 || xx == 15 || yy == 0 || yy == 15 || zz == 0 || zz == 15) {
                        // Vanilla computes the direction with FLOAT arithmetic
                        // (15.0F/2.0F literals) then widens to double; step uses
                        // 0.3F. Using double literals here changes the least
                        // significant bits and accumulates to different block
                        // traversals. Reproduce the exact float-then-widen order.
                        double xd = xx / 15.0F * 2.0F - 1.0F;
                        double yd = yy / 15.0F * 2.0F - 1.0F;
                        double zd = zz / 15.0F * 2.0F - 1.0F;
                        double d = Math.sqrt(xd * xd + yd * yd + zd * zd);
                        double nx = xd / d;
                        double ny = yd / d;
                        double nz = zd / d;
                        params.add(new RayParam(nx, ny, nz, nx * 0.3F, ny * 0.3F, nz * 0.3F));
                    }
                }
            }
        }
        return params;
    }
}
