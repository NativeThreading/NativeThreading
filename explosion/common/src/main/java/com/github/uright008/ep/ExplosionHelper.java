package com.github.uright008.ep;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
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
            long uuidMostSignificantBits,
            long uuidLeastSignificantBits,
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
            float samplingFactor,
            float[] firstBlockDistances) {
    }

    public record EntityDamageResult(
            int entityId,
            long uuidMostSignificantBits,
            long uuidLeastSignificantBits,
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
        float exposure = snapshot.firstBlockDistances == null
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
            com.github.uright008.pc.ChunkGrid chunkGrid) {
        float exposure = snapshot.shouldDamage || snapshot.knockbackMultiplier != 0.0F
                ? getSeenPercentChunkGrid(snapshot, centerX, centerY, centerZ, chunkGrid)
                : 0.0F;
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
                snapshot.uuidMostSignificantBits,
                snapshot.uuidLeastSignificantBits,
                damage, knockbackX, knockbackY, knockbackZ);
    }

    private static float getSeenPercentChunkGrid(EntityDamageSnapshot snapshot,
                                                  double centerX, double centerY, double centerZ,
                                                  com.github.uright008.pc.ChunkGrid chunkGrid) {
        com.github.uright008.pc.ChunkGridBlockGetter bg =
                new com.github.uright008.pc.ChunkGridBlockGetter(chunkGrid);
        Vec3 center = new Vec3(centerX, centerY, centerZ);
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

        ClipContext.Block blockCtx = ClipContext.Block.COLLIDER;
        ClipContext.Fluid fluidCtx = ClipContext.Fluid.NONE;
        int hits = 0, count = 0;
        for (double xx = 0.0; xx <= 1.0; xx += xs) {
            for (double yy = 0.0; yy <= 1.0; yy += ys) {
                for (double zz = 0.0; zz <= 1.0; zz += zs) {
                    double sx = minX + (maxX - minX) * xx + xOffset;
                    double sy = minY + (maxY - minY) * yy;
                    double sz = minZ + (maxZ - minZ) * zz + zOffset;
                    Vec3 from = new Vec3(sx, sy, sz);
                    ClipContext ctx = new ClipContext(from, center, blockCtx, fluidCtx,
                            CollisionContext.empty());
                    BlockHitResult hit = bg.clip(ctx);
                    if (hit.getType() == HitResult.Type.MISS) hits++;
                    count++;
                }
            }
        }
        return (float) hits / count;
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

    public static final int MAX_RAY_STEPS = 128;

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
