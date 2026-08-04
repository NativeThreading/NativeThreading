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
            float samplingFactor,
            float[] firstBlockDistances) {
    }

    public record EntityDamageResult(
            int entityId,
            float damage,
            double kbX,
            double kbY,
            double kbZ) {
        public Vec3 makeKnockback() { return new Vec3(kbX, kbY, kbZ); }
        public Vec3 makeKnockback(double resistance) { return new Vec3(kbX * (1.0 - resistance), kbY * (1.0 - resistance), kbZ * (1.0 - resistance)); }
    }

    public static EntityDamageResult computeEntityDamage(
            EntityDamageSnapshot snapshot,
            double centerX,
            double centerY,
            double centerZ,
            float doubleRadius) {
        float exposure = snapshot.exposurePreset
                ? snapshot.exposure
                : snapshot.firstBlockDistances == null
                        ? snapshot.exposure
                        : getSeenPercentFast(snapshot, centerX, centerY, centerZ);
        return computeEntityDamage(snapshot, centerX, centerY, centerZ, doubleRadius, exposure);
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
        double power = (1.0 - distanceRatio) * exposure * snapshot.knockbackMultiplier;
        double knockbackX = snapshot.feetX - centerX;
        double knockbackY = snapshot.eyeY - centerY;
        double knockbackZ = snapshot.feetZ - centerZ;
        double knockbackLength = Math.sqrt(knockbackX * knockbackX
                + knockbackY * knockbackY + knockbackZ * knockbackZ);
        if (knockbackLength >= 1.0E-5F) {
            double scale = power / knockbackLength;
            knockbackX *= scale;
            knockbackY *= scale;
            knockbackZ *= scale;
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

    /** True if the flat view contains scaffolding or powder snow — blocks whose
     *  collision shape depends on the querying entity. When present, exposure
     *  must be computed with the real entity context (vanilla-exact) instead of
     *  the context-free flat shapes. */
    public static boolean hasEntityContextBlocks(WorldReadView<net.minecraft.world.level.block.state.BlockState> worldView) {
        if (!(worldView instanceof WorldReadViewImpl impl)) return false;
        BlockState[] states = impl.states();
        for (BlockState state : states) {
            Block block = state.getBlock();
            if (block instanceof ScaffoldingBlock || block instanceof PowderSnowBlock) return true;
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
        double[][] boxes = new double[gridSize][];
        double[] fullBox = new double[] {0.0, 0.0, 0.0, 1.0, 1.0, 1.0};
        for (int i = 0; i < gridSize; i++) {
            VoxelShape shape = shapes != null ? shapes[i] : null;
            if (shape == null) {
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
        float samplingFactor = snapshot.samplingFactor;
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
                if (tMaxX < tMaxZ) { if (stepX == 0) break; x += stepX; tMaxX += tDeltaX; }
                else                { if (stepZ == 0) break; z += stepZ; tMaxZ += tDeltaZ; }
            } else {
                if (tMaxY < tMaxZ) { if (stepY == 0) break; y += stepY; tMaxY += tDeltaY; }
                else                { if (stepZ == 0) break; z += stepZ; tMaxZ += tDeltaZ; }
            }
        }
        return false;
    }

    static boolean rayIntersectsBlockFlat(double fx, double fy, double fz,
                                          double tx, double ty, double tz,
                                          WorldReadView<net.minecraft.world.level.block.state.BlockState> worldView) {
        // Fast path only when every block the DDA will visit is inside the flat
        // view; otherwise fall back to the reference implementation.
        if (worldView instanceof WorldReadViewImpl impl
                && rayWithinBounds(impl, fx, fy, fz, tx, ty, tz)) {
            return rayIntersectsBlockFlatFast(fx, fy, fz, tx, ty, tz, impl);
        }
        return rayIntersectsBlockFlatSlow(fx, fy, fz, tx, ty, tz, worldView);
    }

    static boolean rayWithinBounds(WorldReadViewImpl worldView,
                                   double fx, double fy, double fz,
                                   double tx, double ty, double tz) {
        return blockSpanWithinBounds(worldView.minX(), worldView.maxX(), fx, tx)
                && blockSpanWithinBounds(worldView.minY(), worldView.maxY(), fy, ty)
                && blockSpanWithinBounds(worldView.minZ(), worldView.maxZ(), fz, tz);
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
                    if (tMaxX < tMaxZ) { if (stepX == 0) break; x += stepX; index += stepX; tMaxX += tDeltaX; }
                    else                { if (stepZ == 0) break; z += stepZ; index += stepZIndex; tMaxZ += tDeltaZ; }
                } else {
                    if (tMaxY < tMaxZ) { if (stepY == 0) break; y += stepY; index += stepYIndex; tMaxY += tDeltaY; }
                    else                { if (stepZ == 0) break; z += stepZ; index += stepZIndex; tMaxZ += tDeltaZ; }
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
                if (tMaxX < tMaxZ) { if (stepX == 0) break; x += stepX; index += stepX; tMaxX += tDeltaX; }
                else                { if (stepZ == 0) break; z += stepZ; index += stepZIndex; tMaxZ += tDeltaZ; }
            } else {
                if (tMaxY < tMaxZ) { if (stepY == 0) break; y += stepY; index += stepYIndex; tMaxY += tDeltaY; }
                else                { if (stepZ == 0) break; z += stepZ; index += stepZIndex; tMaxZ += tDeltaZ; }
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

    public static float getSeenPercentFast(EntityDamageSnapshot snapshot,
                                           double centerX, double centerY, double centerZ) {
        double minX = snapshot.minX;
        double minY = snapshot.minY;
        double minZ = snapshot.minZ;
        double maxX = snapshot.maxX;
        double maxY = snapshot.maxY;
        double maxZ = snapshot.maxZ;
        float[] distances = snapshot.firstBlockDistances;
        float samplingFactor = snapshot.samplingFactor;
        double xs = 1.0 / ((maxX - minX) * samplingFactor + 1.0);
        double ys = 1.0 / ((maxY - minY) * samplingFactor + 1.0);
        double zs = 1.0 / ((maxZ - minZ) * samplingFactor + 1.0);
        double xOff = (1.0 - Math.floor(1.0 / xs) * xs) / 2.0;
        double zOff = (1.0 - Math.floor(1.0 / zs) * zs) / 2.0;
        if (xs < 0.0 || ys < 0.0 || zs < 0.0) return 0.0F;

        int hits = 0;
        int count = 0;
        for (double xx = 0.0; xx <= 1.0; xx += xs) {
            for (double yy = 0.0; yy <= 1.0; yy += ys) {
                for (double zz = 0.0; zz <= 1.0; zz += zs) {
                    double sampleX = minX + (maxX - minX) * xx + xOff;
                    double sampleY = minY + (maxY - minY) * yy;
                    double sampleZ = minZ + (maxZ - minZ) * zz + zOff;
                    double dx = sampleX - centerX;
                    double dy = sampleY - centerY;
                    double dz = sampleZ - centerZ;
                    double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    double inverseDistance = 1.0 / distance;
                    int gridX = Math.max(0, Math.min(15, (int) ((dx * inverseDistance + 1.0) * 7.5 + 0.5)));
                    int gridY = Math.max(0, Math.min(15, (int) ((dy * inverseDistance + 1.0) * 7.5 + 0.5)));
                    int gridZ = Math.max(0, Math.min(15, (int) ((dz * inverseDistance + 1.0) * 7.5 + 0.5)));
                    int distanceX = Math.min(gridX, 15 - gridX);
                    int distanceY = Math.min(gridY, 15 - gridY);
                    int distanceZ = Math.min(gridZ, 15 - gridZ);
                    if (distanceX <= distanceY && distanceX <= distanceZ) gridX = gridX < 8 ? 0 : 15;
                    else if (distanceY <= distanceZ) gridY = gridY < 8 ? 0 : 15;
                    else gridZ = gridZ < 8 ? 0 : 15;
                    int rayIndex = RAY_INDEX_BY_GRID[gridX][gridY][gridZ];
                    if (rayIndex >= 0 && rayIndex < distances.length && distance <= distances[rayIndex]) hits++;
                    count++;
                }
            }
        }
        return (float) hits / count;
    }

    private static float vanillaDamage(float doubleRadius, double distanceRatio, float exposure) {
        double power = (1.0 - distanceRatio) * exposure;
        return (float) ((power * power + power) / 2.0 * 7.0 * doubleRadius + 1.0);
    }

    public static Vec3 knockback(double x, double y, double z, double power) {
        double length = Math.sqrt(x * x + y * y + z * z);
        return length < 1.0E-5F ? Vec3.ZERO : new Vec3(x / length * power, y / length * power, z / length * power);
    }

    /** Maximum ray steps a single trace may walk. Must cover the largest
     *  blast radius a server can produce: vanilla has no step cap (its loop
     *  runs while remainingPower > 0), and NT's region grows as
     *  ceil(radius*1.3/0.225)*0.3 blocks per axis, so the step budget must
     *  cover that reach. 512 steps covers radius <= 88 (the old 128 capped
     *  rays at radius ~22, truncating bigger blasts). */
    public static final int MAX_RAY_STEPS = 512;

    /** Step budget for a given explosion radius: the number of 0.3-block steps
     *  needed to walk the region reach ({@code ceil(radius*1.3/0.225)*0.3}
     *  blocks), plus one so the ray terminates on or past the boundary exactly
     *  as vanilla's power-decay loop would. */
    public static int rayMaxSteps(float radius) {
        int reach = (int) Math.ceil(Math.ceil(radius * 1.3F / 0.22500001F) * 0.3);
        return (int) Math.ceil(reach / 0.3) + 1;
    }

    public static final List<RayParam> RAY_PARAMS = generateRayParams();
    public static final int[][][] RAY_INDEX_BY_GRID = buildRayIndexGrid();
    public static final int[][] RAY_DELTAS = generateRayDeltas();

    // ── Ray generation ───────────────────────────

    private static List<RayParam> generateRayParams() {
        List<RayParam> params = new ArrayList<>();
        for (int xx = 0; xx < 16; xx++) {
            for (int yy = 0; yy < 16; yy++) {
                for (int zz = 0; zz < 16; zz++) {
                    if (xx == 0 || xx == 15 || yy == 0 || yy == 15 || zz == 0 || zz == 15) {
                        double xd = xx / 15.0 * 2.0 - 1.0;
                        double yd = yy / 15.0 * 2.0 - 1.0;
                        double zd = zz / 15.0 * 2.0 - 1.0;
                        double d = Math.sqrt(xd * xd + yd * yd + zd * zd);
                        double nx = xd / d;
                        double ny = yd / d;
                        double nz = zd / d;
                        params.add(new RayParam(nx, ny, nz, nx * 0.3, ny * 0.3, nz * 0.3));
                    }
                }
            }
        }
        return params;
    }

    private static int[][][] buildRayIndexGrid() {
        int[][][] grid = new int[16][16][16];
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    grid[x][y][z] = -1;
        for (int r = 0; r < RAY_PARAMS.size(); r++) {
            int idx = 0;
            outer:
            for (int xx = 0; xx < 16; xx++)
                for (int yy = 0; yy < 16; yy++)
                    for (int zz = 0; zz < 16; zz++)
                        if (xx == 0 || xx == 15 || yy == 0 || yy == 15 || zz == 0 || zz == 15) {
                            if (idx == r) { grid[xx][yy][zz] = r; break outer; }
                            idx++;
                        }
        }
        return grid;
    }

    private static int[][] generateRayDeltas() {
        int[][] deltas = new int[RAY_PARAMS.size()][MAX_RAY_STEPS];
        for (int r = 0; r < RAY_PARAMS.size(); r++) {
            RayParam p = RAY_PARAMS.get(r);
            double x = 0.0, y = 0.0, z = 0.0;
            int px = net.minecraft.util.Mth.floor(x);
            int py = net.minecraft.util.Mth.floor(y);
            int pz = net.minecraft.util.Mth.floor(z);
            for (int s = 0; s < MAX_RAY_STEPS; s++) {
                x += p.stepX();
                y += p.stepY();
                z += p.stepZ();
                int cx = net.minecraft.util.Mth.floor(x);
                int cy = net.minecraft.util.Mth.floor(y);
                int cz = net.minecraft.util.Mth.floor(z);
                int dx = cx - px, dy = cy - py, dz = cz - pz;
                deltas[r][s] = (dx & 0xFF) | ((dy & 0xFF) << 8) | ((dz & 0xFF) << 16);
                px = cx; py = cy; pz = cz;
            }
        }
        return deltas;
    }

    public static List<RayParam> buildRayParams(int gridSize) {
        List<RayParam> p = new ArrayList<>();
        for (int xx = 0; xx < gridSize; xx++)
            for (int yy = 0; yy < gridSize; yy++)
                for (int zz = 0; zz < gridSize; zz++)
                    if (xx == 0 || xx == gridSize - 1 || yy == 0 || yy == gridSize - 1 || zz == 0 || zz == gridSize - 1) {
                        double xd = xx / (gridSize - 1.0) * 2.0 - 1.0;
                        double yd = yy / (gridSize - 1.0) * 2.0 - 1.0;
                        double zd = zz / (gridSize - 1.0) * 2.0 - 1.0;
                        double d = Math.sqrt(xd * xd + yd * yd + zd * zd);
                        double nx = xd / d, ny = yd / d, nz = zd / d;
                        p.add(new RayParam(nx, ny, nz, nx * 0.3, ny * 0.3, nz * 0.3));
                    }
        return p;
    }
}
