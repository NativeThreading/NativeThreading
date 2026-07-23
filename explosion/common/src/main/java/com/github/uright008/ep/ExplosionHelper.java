package com.github.uright008.ep;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class ExplosionHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExplosionHelper.class);

    private ExplosionHelper() {}

    public record RayParam(double xd, double yd, double zd,
                           double stepX, double stepY, double stepZ) {}
    public record EntityDamageResult(Entity entity, float damage, double kbX, double kbY, double kbZ) {
        public Vec3 makeKnockback() { return new Vec3(kbX, kbY, kbZ); }
        public Vec3 makeKnockback(double resistance) { return new Vec3(kbX * (1.0 - resistance), kbY * (1.0 - resistance), kbZ * (1.0 - resistance)); }
    }

    public static Vec3 knockback(double x, double y, double z, double power) {
        double length = Math.sqrt(x * x + y * y + z * z);
        return length < 1.0E-5F ? Vec3.ZERO : new Vec3(x / length * power, y / length * power, z / length * power);
    }

    public static final int MAX_RAY_STEPS = 128;

    public static final List<RayParam> RAY_PARAMS = generateRayParams();
    public static final int[][][] RAY_INDEX_BY_GRID = buildRayIndexGrid();
    public static final int[][] RAY_DELTAS = generateRayDeltas();

    /** Pre-computed: block state id → has full cube collision.  Indexed by {@code Block.getId(BlockState)}. */
    public static boolean[] FULL_CUBE;

    /** Must be called once during mod init. */
    public static void initFullCubeCache() {
        int maxId = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                maxId = Math.max(maxId, Block.getId(state));
            }
        }
        FULL_CUBE = new boolean[maxId + 1]; // defaults to false
        net.minecraft.core.BlockPos zero = net.minecraft.core.BlockPos.ZERO;
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                try {
                    boolean fullCube = state.isCollisionShapeFullBlock(null, zero);
                    FULL_CUBE[Block.getId(state)] = fullCube;
                } catch (NullPointerException ignored) {
                    // Some blocks (shulker boxes etc.) require a Level context
                    // for collision shape checks. Skip them — they won't be
                    // full cubes at explosion time either.
                } catch (Exception e) {
                    LOGGER.warn("Failed to check full-cube for block {} state {}", block, state, e);
                }
            }
        }
    }

    public static boolean isFullCube(BlockState state) {
        boolean[] fullCube = FULL_CUBE;
        int id = net.minecraft.world.level.block.Block.getId(state);
        return fullCube != null && id >= 0 && id < fullCube.length && fullCube[id];
    }

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
