package com.github.uright008.ep;

import com.github.uright008.pc.ChunkGrid;
import com.github.uright008.pc.ParallelThreadPool;
import com.github.uright008.pc.ParallelWorker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Main-thread ray pipeline for one explosion: draw the vanilla-ordered ray
 *  powers, build the flat view, trace the 1352 rays on the worker pool (serial
 *  retrace on failure), fold the grid into a BlockPos list. Pure computation
 *  over an {@link ExplosionContext} — never touches a ServerExplosion. */
public final class ExplosionBlockStage {

    /** The exploded-position list plus the flat view the entity stage needs
     *  for its exposure DDA. Returning the view explicitly removes the
     *  "entity stage depends on the ray stage having run first" implicit
     *  ordering that a shared mixin field would hide. */
    public record Result(List<BlockPos> blocks, WorldReadViewImpl worldView) {}

    @FunctionalInterface
    public interface ResistanceCalculator {
        float apply(BlockPos pos, BlockState block, FluidState fluid, float baseResistance);
    }

    @FunctionalInterface
    public interface BlockExplodeDecider {
        boolean shouldExplode(BlockPos pos, BlockState block, float remainingPower);
    }

    // Reusable flat-view buffers. Explosions run serially on the main thread
    // and the worker phase is joined before any reuse, so static caches are
    // safe and avoid allocating three arrays (block states, shapes, box
    // table) per explosion — ~30k arrays/tick under sustained TNT chains.
    private static final AtomicReference<BlockState[]> FLAT_BLOCKS_CACHE = new AtomicReference<>();
    private static final AtomicReference<double[][]> SHAPE_BOXES_CACHE = new AtomicReference<>();
    private static final AtomicReference<float[]> RAY_POWERS_CACHE = new AtomicReference<>();
    // One mutable pos per worker thread (traceRay is called ~1352× per
    // explosion); avoids allocating 11k+ MutableBlockPos per tick.
    private static final ThreadLocal<BlockPos.MutableBlockPos> WORKER_POS = ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    private static final Logger LOGGER = LoggerFactory.getLogger("native-threading:explosion:block");

    private static final ResistanceCalculator DEFAULT_RESISTANCE_CALC = (pos, block, fluid, baseRes) -> {
        if (!block.isAir() || !fluid.isEmpty()) {
            return (baseRes + 0.3F) * 0.3F;
        }
        return 0.0F;
    };

    private static final BlockExplodeDecider DEFAULT_EXPLODE_DECIDER = (pos, block, remainingPower) -> remainingPower > 0.0F;

    private ExplosionBlockStage() {}

    public static Result compute(ExplosionContext ctx, ChunkGrid chunkGrid) {
        List<ExplosionHelper.RayParam> rays = ExplosionHelper.RAY_PARAMS;
        int rayCount = rays.size();
        int cpuCores = Runtime.getRuntime().availableProcessors();
        int numThreads = Math.min(ParallelThreadPool.getParallelism(), Math.min(cpuCores, Math.max(2, rayCount / 64)));

        float[] rayPowers = RAY_POWERS_CACHE.getAndSet(null);
        if (rayPowers == null || rayPowers.length < rayCount) rayPowers = new float[rayCount];
        final float radiusF = ctx.radius();
        // Random powers are drawn on the main thread, one nextFloat per ray,
        // in exactly the vanilla iteration order (xx→yy→zz over the 16³ grid
        // boundary). Reusing level.getRandom() keeps the drawn sequence
        // identical to vanilla; the worker rays consume these precomputed
        // values and never touch an RNG themselves, so no cross-thread RNG
        // access exists.
        for (int i = 0; i < rayCount; i++) {
            rayPowers[i] = radiusF * (0.7F + ctx.level().getRandom().nextFloat() * 0.6F);
        }

        ExplosionRayBounds bounds = ExplosionRayBounds.forExplosion(ctx.center(), ctx.radius());
        final int minX = bounds.minX();
        final int minY = bounds.minY();
        final int minZ = bounds.minZ();
        final int maxX = bounds.maxX();
        final int maxY = bounds.maxY();
        final int maxZ = bounds.maxZ();
        final int strideY = maxX - minX + 1;
        final int strideZ = strideY * (maxY - minY + 1);
        final int gridSize = strideZ * (maxZ - minZ + 1);

        int perWorker = rayCount / numThreads;
        int extra = rayCount % numThreads;
        record RayRange(int start, int end, BitSet grid) {}
        List<RayRange> ranges = new ArrayList<>(numThreads);
        for (int t = 0; t < numThreads; t++) {
            int start = t * perWorker + Math.min(t, extra);
            int end = start + perWorker + (t < extra ? 1 : 0);
            if (start < end) ranges.add(new RayRange(start, end, new BitSet(gridSize)));
        }

        BlockState[] flatBlocks = FLAT_BLOCKS_CACHE.getAndSet(null);
        if (flatBlocks == null || flatBlocks.length < gridSize) flatBlocks = new BlockState[gridSize];
        ExplosionFlatViewBuilder.fillSectioned(flatBlocks, minX, minY, minZ, maxX, maxY, maxZ,
                strideY, strideZ, chunkGrid);

        // Collision boxes are resolved from the per-BlockState cache — the same
        // getCollisionShape(null, null) call the previous per-cell loop made,
        // but one map hit per distinct block state instead of per cell. Workers
        // never touch BlockState objects; the box table alone drives the DDA.
        final WorldReadViewImpl worldView = new WorldReadViewImpl(
                flatBlocks, null,
                ExplosionHelper.flattenShapeBoxesReused(flatBlocks, null, gridSize, SHAPE_BOXES_CACHE),
                minX, minY, minZ, maxX, maxY, maxZ, strideY, strideZ);

        final ServerExplosion self = ctx.self();
        final boolean isDefaultCalc = ctx.damageCalculator().getClass() == ExplosionDamageCalculator.class;
        final ResistanceCalculator resistanceCalc;
        final BlockExplodeDecider explodeDecider;

        if (isDefaultCalc) {
            resistanceCalc = DEFAULT_RESISTANCE_CALC;
            explodeDecider = DEFAULT_EXPLODE_DECIDER;
        } else if (ctx.damageCalculator() instanceof net.minecraft.world.level.EntityBasedExplosionDamageCalculator
                && ctx.source() != null) {
            final Entity entity = ctx.source();
            final ServerLevel level = ctx.level();
            resistanceCalc = (pos, block, fluid, baseRes) -> {
                if (!block.isAir() || !fluid.isEmpty()) {
                    float res = Math.max(block.getBlock().getExplosionResistance(),
                            fluid.getExplosionResistance());
                    res = entity.getBlockExplosionResistance(self, level, pos, block, fluid, res);
                    return (res + 0.3F) * 0.3F;
                }
                return 0.0F;
            };
            explodeDecider = (pos, block, remainingPower) ->
                    entity.shouldBlockExplode(self, level, pos, block, remainingPower);
        } else {
            final ExplosionDamageCalculator calc = ctx.damageCalculator();
            final ServerLevel level = ctx.level();
            resistanceCalc = (pos, block, fluid, baseRes) -> {
                Optional<Float> resistance = calc.getBlockExplosionResistance(self, level, pos, block, fluid);
                return resistance.map(r -> (r + 0.3F) * 0.3F).orElse(0.0F);
            };
            explodeDecider = (pos, block, remainingPower) ->
                    calc.shouldBlockExplode(self, level, pos, block, remainingPower);
        }

        final float[] pow = rayPowers;
        final double centerX = ctx.center().x, centerY = ctx.center().y, centerZ = ctx.center().z;
        final int worldMinY = ctx.level().getMinY(), worldMaxY = ctx.level().getMaxY();
        final float radius = ctx.radius();
        BitSet grid = new BitSet(gridSize);
        try {
            List<BitSet> workerGrids = ParallelWorker.mapEach(ParallelThreadPool.getPool("Explosion"),
                    ranges, range -> {
                        for (int i = range.start; i < range.end; i++)
                            traceRay(rays.get(i), i, range.grid, minX, minY, minZ, maxX, maxY, maxZ,
                                    worldView, strideY, strideZ, pow[i], radius,
                                    centerX, centerY, centerZ, worldMinY, worldMaxY,
                                    resistanceCalc, explodeDecider);
                        return range.grid;
                    }, 5);
            for (BitSet wg : workerGrids) grid.or(wg);
        } catch (RuntimeException e) {
            // Workers failed — recompute serially with the already-drawn ray
            // powers instead of falling back to vanilla. A vanilla fallback
            // would draw the 1352 nextFloat values AGAIN, advancing level
            // random twice (2724 steps vs vanilla's 1352) and shifting every
            // later RNG consumer (block drops, fire placement, shuffle).
            // Serial retrace costs the same as vanilla's own main-thread
            // pass and never re-draws the RNG.
            LOGGER.error("Explosion ray workers failed; tracing rays serially", e);
            for (int i = 0; i < rayCount; i++)
                traceRay(rays.get(i), i, grid, minX, minY, minZ, maxX, maxY, maxZ,
                        worldView, strideY, strideZ, pow[i], radius,
                        centerX, centerY, centerZ, worldMinY, worldMaxY,
                        resistanceCalc, explodeDecider);
        }

        List<BlockPos> result = new ArrayList<>(gridSize);
        for (int z = minZ; z <= maxZ; z++) {
            int zOff = (z - minZ) * strideZ;
            for (int y = minY; y <= maxY; y++) {
                int yzOff = zOff + (y - minY) * strideY;
                for (int x = minX; x <= maxX; x++)
                    if (grid.get(yzOff + (x - minX)))
                        result.add(new BlockPos(x, y, z));
            }
        }

        // Workers have joined; hand the buffers back for reuse.
        FLAT_BLOCKS_CACHE.set(flatBlocks);
        SHAPE_BOXES_CACHE.set(worldView.shapeBoxes());
        RAY_POWERS_CACHE.set(rayPowers);

        return new Result(result, worldView);
    }

    /** Vanilla-exact march: float accumulation from the exact centre,
     *  flooring each step — identical to ServerExplosion.calculateExplodedPositions.
     *  Step uses the precomputed direction*0.3F (vanilla's 0.3F, not the
     *  double literal 0.3) so accumulation matches bit for bit. */
    private static void traceRay(ExplosionHelper.RayParam ray, int rayIndex,
                                 BitSet grid, int minX, int minY, int minZ,
                                 int maxX, int maxY, int maxZ, WorldReadViewImpl worldView,
                                 int strideY, int strideZ,
                                 float initialPower, float radius,
                                 double centerX, double centerY, double centerZ,
                                 int worldMinY, int worldMaxY,
                                 ResistanceCalculator resistanceCalc,
                                 BlockExplodeDecider explodeDecider) {
        float remainingPower = initialPower;
        final int gMinX = minX, gMinY = minY, gMinZ = minZ;
        final int gMaxX = maxX, gMaxY = maxY, gMaxZ = maxZ;
        final int MAX = ExplosionHelper.rayMaxSteps(radius);
        final int strideY_ = strideY, strideZ_ = strideZ;
        final BlockPos.MutableBlockPos pos = WORKER_POS.get();

        double xp = centerX, yp = centerY, zp = centerZ;
        final double sx = ray.stepX(), sy = ray.stepY(), sz = ray.stepZ();

        for (int s = 0; s < MAX && remainingPower > 0.0F; remainingPower -= 0.22500001F, s++) {
            int bx = net.minecraft.util.Mth.floor(xp);
            int by = net.minecraft.util.Mth.floor(yp);
            int bz = net.minecraft.util.Mth.floor(zp);
            pos.set(bx, by, bz);
            // Vanilla breaks on !level.isInWorldBounds(pos) — the build-height
            // check matters: cells above the world are read as AIR by the flat
            // view, so without this the ray would keep marching past the world
            // ceiling and set grid bits there (feeding createFire). x/z are
            // unbounded on servers, only y needs the check.
            if (by < worldMinY || by > worldMaxY) break;
            if (bx < gMinX || bx > gMaxX || by < gMinY || by > gMaxY || bz < gMinZ || bz > gMaxZ) break;

            BlockState block = worldView.getBlockStateUnchecked(bx, by, bz);
            FluidState fluid = block.getFluidState();
            if (!block.isAir() || !fluid.isEmpty()) {
                float baseRes = Math.max(block.getBlock().getExplosionResistance(),
                        fluid.getExplosionResistance());
                remainingPower -= resistanceCalc.apply(pos, block, fluid, baseRes);
            }
            if (remainingPower > 0.0F && explodeDecider.shouldExplode(pos, block, remainingPower)) {
                if (bx >= gMinX && bx <= gMaxX && by >= gMinY && by <= gMaxY && bz >= gMinZ && bz <= gMaxZ)
                    grid.set((bx - gMinX) + (by - gMinY) * strideY_ + (bz - gMinZ) * strideZ_);
            }
            xp += sx; yp += sy; zp += sz;
        }
    }
}
