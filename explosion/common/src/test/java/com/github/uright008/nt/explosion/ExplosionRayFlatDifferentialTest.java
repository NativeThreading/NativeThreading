package com.github.uright008.nt.explosion;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Differential test for the explosion flat-view ray paths in
 * {@link ExplosionRayCast}.
 *
 * <p>{@link ExplosionRayCast#rayIntersectsBlockFlatBoxed} walks the box table
 * precomputed on the main thread and must return bit-identical booleans to an
 * independent reference DDA that resolves each cell's {@code BlockState} shape
 * directly ({@link #referenceRay}) and to the production dispatcher
 * ({@link ExplosionRayCast#rayIntersectsBlockFlat}, which applies vanilla's
 * traverseBlocks backoff first) — for in-bounds, out-of-bounds, zero-length,
 * boundary-touching, axis-aligned and 3D-diagonal rays.</p>
 *
 * <p>Production never resolves a shape on the worker: the box table is built by
 * {@link ExplosionShapeBoxes} on the main thread (live level + real cell
 * position). The reference DDA here is test-only and only valid for states whose
 * collision shape is a pure function of the state; the fixture uses static
 * blocks only.</p>
 */
class ExplosionRayFlatDifferentialTest {

    private static final int MIN_X = 10, MIN_Y = 40, MIN_Z = 20;
    private static final int MAX_X = 40, MAX_Y = 80, MAX_Z = 60;
    private static final int STRIDE_Y = MAX_X - MIN_X + 1;
    private static final int STRIDE_Z = STRIDE_Y * (MAX_Y - MIN_Y + 1);

    /** Test-only shape lookup context: static states short-circuit to vanilla's
     *  baked cache, so the level argument is never dereferenced. */
    private static final net.minecraft.world.level.BlockGetter TEST_LEVEL = EmptyBlockGetter.INSTANCE;
    private static final BlockPos.MutableBlockPos REFERENCE_POS = new BlockPos.MutableBlockPos();

    /** Ray function over the flat view; switches between reference/boxed/dispatcher. */
    @FunctionalInterface
    private interface RayTracer {
        boolean hit(double fx, double fy, double fz,
                    double tx, double ty, double tz, WorldReadViewImpl view);
    }

    private static final RayTracer REFERENCE = ExplosionRayFlatDifferentialTest::referenceRay;
    private static final RayTracer BOXED = ExplosionRayCast::rayIntersectsBlockFlatBoxed;
    private static final RayTracer DISPATCHER = ExplosionRayCast::rayIntersectsBlockFlat;

    // ── Fixture: deterministic 31×41×41 grid with obstacles ──

    private static int index(int x, int y, int z) {
        return (x - MIN_X) + (y - MIN_Y) * STRIDE_Y + (z - MIN_Z) * STRIDE_Z;
    }

    private static void setBlock(BlockState[] states, int x, int y, int z, BlockState state) {
        states[index(x, y, z)] = state;
    }

    private static WorldReadViewImpl buildView() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        BlockState[] states = new BlockState[STRIDE_Z * (MAX_Z - MIN_Z + 1)];
        Arrays.fill(states, Blocks.AIR.defaultBlockState());

        // Single full stone.
        setBlock(states, 20, 60, 30, Blocks.STONE.defaultBlockState());
        // Solid stone wall on the z=55 plane.
        for (int y = 50; y <= 70; y++)
            for (int x = 15; x <= 25; x++)
                setBlock(states, x, y, 55, Blocks.STONE.defaultBlockState());
        // Partial-height slab row (exercises the non-full VoxelShape branch).
        for (int x = 30; x <= 35; x++)
            for (int z = 25; z <= 28; z++)
                setBlock(states, x, 45, z, Blocks.STONE_SLAB.defaultBlockState());

        double[][] boxes = ExplosionShapeBoxes.flattenShapeBoxes(
                states, TEST_LEVEL, MIN_X, MIN_Y, MIN_Z, STRIDE_Y, STRIDE_Z, states.length);
        return new WorldReadViewImpl(states, boxes,
                MIN_X, MIN_Y, MIN_Z, MAX_X, MAX_Y, MAX_Z, STRIDE_Y, STRIDE_Z);
    }

    // ── Per-ray differential: boxed == reference == dispatcher ──

    @Test
    void rayDifferential_boxedEqualsReferenceEqualsDispatcher() {
        WorldReadViewImpl view = buildView();
        double cx = 25.0, cy = 60.0, cz = 40.0;

        double[][] starts = {
                // Inside view, near obstacles.
                {22.0, 62.0, 42.0},
                {15.5, 55.0, 55.0},        // next to the stone wall
                {31.0, 45.5, 26.0},        // above the slab row
                {19.5, 60.0, 30.0},        // next to the single stone
                // Pure horizontal and pure vertical.
                {25.0, 60.0, 59.5},
                {25.0, 41.5, 40.0},
                // Start exactly on the view boundary.
                {10.0, 60.0, 40.0},
                {40.0, 60.0, 40.0},
                {25.0, 40.0, 40.0},
                {25.0, 80.0, 40.0},
                {25.0, 60.0, 20.0},
                {25.0, 60.0, 60.0},
                // Just inside each corner.
                {10.5, 40.5, 20.5},
                {39.5, 79.5, 59.5},
                // Outside the view — must use the bounds-checked walker.
                {0.0, 0.0, 0.0},
                {-5.0, 45.0, 15.0},
                {50.0, 90.0, 70.0},
                {9.5, 60.0, 40.0},
                {25.0, 60.0, 19.5},
        };

        for (double[] s : starts) {
            assertRayMatchesAll(view, s[0], s[1], s[2], cx, cy, cz);
            assertRayMatchesAll(view, cx, cy, cz, s[0], s[1], s[2]);
        }

        // Zero-length rays.
        assertRayMatchesAll(view, cx, cy, cz, cx, cy, cz);
        assertRayMatchesAll(view, 22.3, 62.4, 42.1, 22.3, 62.4, 42.1);

        // Whole-view diagonal between boundary points, both directions.
        assertRayMatchesAll(view, 10.0, 40.0, 20.0, 40.0, 80.0, 60.0);
        assertRayMatchesAll(view, 40.0, 80.0, 60.0, 10.0, 40.0, 20.0);

        // Crossing the view from outside to outside.
        assertRayMatchesAll(view, 5.0, 60.0, 40.0, 45.0, 60.0, 40.0);
        // Start on the boundary, end outside.
        assertRayMatchesAll(view, 10.0, 60.0, 40.0, 42.0, 60.0, 40.0);
        // Entirely negative coordinates (outside the view).
        assertRayMatchesAll(view, -10.0, -20.0, -30.0, -5.0, -15.0, -25.0);
        // Tiny same-block ray (lenSq >= 1e-7).
        assertRayMatchesAll(view, 20.1, 60.1, 30.1, 20.2, 60.2, 30.2);
        // Ray hugging the boundary plane.
        assertRayMatchesAll(view, 10.0, 55.0, 30.0, 12.0, 57.0, 33.0);
    }

    private static void assertRayMatchesAll(WorldReadViewImpl view,
                                            double fx, double fy, double fz,
                                            double tx, double ty, double tz) {
        boolean reference = REFERENCE.hit(fx, fy, fz, tx, ty, tz, view);
        boolean boxed = BOXED.hit(fx, fy, fz, tx, ty, tz, view);
        boolean dispatcher = DISPATCHER.hit(fx, fy, fz, tx, ty, tz, view);
        String ray = String.format("ray (%s,%s,%s)->(%s,%s,%s)", fx, fy, fz, tx, ty, tz);
        assertThat(boxed).as(ray + " boxed walker").isEqualTo(reference);
        assertThat(dispatcher).as(ray + " dispatcher").isEqualTo(reference);
    }

    // ── Behavior pins: the boxed path must actually block / not block ──

    @Test
    void behaviorPins_blockingAndOpenRays() {
        WorldReadViewImpl view = buildView();

        // Crosses the stone wall (z=55 plane) — must hit.
        assertThat(BOXED.hit(12.5, 50.5, 55.5, 24.5, 70.5, 55.5, view)).isTrue();
        // Hits the single stone at (20,60,30).
        assertThat(BOXED.hit(20.5, 59.5, 30.5, 19.5, 60.5, 30.5, view)).isTrue();
        // Open-air short ray — must not hit.
        assertThat(BOXED.hit(12.5, 42.5, 22.5, 14.5, 44.5, 24.5, view)).isFalse();
        // Partial slab (non-full VoxelShape): the ray from y=46.5 down through
        // the slab's lower half must hit.
        assertThat(BOXED.hit(32.5, 46.5, 26.5, 32.5, 43.5, 26.5, view)).isTrue();

        // Reference agrees on the same rays.
        assertThat(REFERENCE.hit(12.5, 50.5, 55.5, 24.5, 70.5, 55.5, view)).isTrue();
        assertThat(REFERENCE.hit(20.5, 59.5, 30.5, 19.5, 60.5, 30.5, view)).isTrue();
        assertThat(REFERENCE.hit(12.5, 42.5, 22.5, 14.5, 44.5, 24.5, view)).isFalse();
        assertThat(REFERENCE.hit(32.5, 46.5, 26.5, 32.5, 43.5, 26.5, view)).isTrue();
    }

    // ── getSeenPercent hit-count / fraction differential ──

    @Test
    void seenPercent_dispatcherMatchesReference_bitIdentical() {
        WorldReadViewImpl view = buildView();
        double cx = 20.0, cy = 60.0, cz = 55.0; // center on the wall plane

        // Entity box entirely inside the view, on the near side of the wall.
        ExplosionEntityDamageComputer.EntityDamageSnapshot inBounds =
                snapshot(16.0, 55.0, 48.0, 24.0, 63.0, 54.0);

        float reference = seenPercent(inBounds, cx, cy, cz, view, REFERENCE);
        float boxed = seenPercent(inBounds, cx, cy, cz, view, BOXED);
        float dispatcher = seenPercent(inBounds, cx, cy, cz, view, DISPATCHER);
        assertThat(boxed).as("boxed seenPercent must equal reference").isEqualTo(reference);
        assertThat(dispatcher).as("dispatcher seenPercent must equal reference").isEqualTo(reference);

        // Production getSeenPercentFromFlatView goes through the dispatcher; the
        // resulting damage/knockback must equal a reference-exposure end to end.
        ExplosionEntityDamageComputer.EntityDamageSnapshot damageSnapshot = new ExplosionEntityDamageComputer.EntityDamageSnapshot(
                42, 19.0, 55.5, 49.5, 60.0,
                16.0, 55.0, 48.0, 24.0, 63.0, 54.0,
                true, 1.0F, 0.0F, false, 0.0);
        ExplosionEntityDamageComputer.EntityDamageResult viaView = ExplosionEntityDamageComputer.computeEntityDamage(
                damageSnapshot, cx, cy, cz, 32.0F, view);
        ExplosionEntityDamageComputer.EntityDamageSnapshot referenceSnapshot = new ExplosionEntityDamageComputer.EntityDamageSnapshot(
                42, 19.0, 55.5, 49.5, 60.0,
                16.0, 55.0, 48.0, 24.0, 63.0, 54.0,
                true, 1.0F, reference, true, 0.0);
        ExplosionEntityDamageComputer.EntityDamageResult viaReferenceExposure = ExplosionEntityDamageComputer.computeEntityDamage(
                referenceSnapshot, cx, cy, cz, 32.0F, view);
        assertThat(viaView.damage()).isEqualTo(viaReferenceExposure.damage());
        assertThat(viaView.kbX()).isEqualTo(viaReferenceExposure.kbX());
        assertThat(viaView.kbY()).isEqualTo(viaReferenceExposure.kbY());
        assertThat(viaView.kbZ()).isEqualTo(viaReferenceExposure.kbZ());
    }

    @Test
    void seenPercent_dispatcherMixesInBoundsAndOutOfBounds_bitIdentical() {
        WorldReadViewImpl view = buildView();
        double cx = 25.0, cy = 60.0, cz = 40.0;

        // Box straddles the view's minZ=20 plane: near-side samples take the
        // out-of-bounds walker, far-side samples the incremental walker. The
        // mixed fraction must equal the all-reference fraction.
        ExplosionEntityDamageComputer.EntityDamageSnapshot straddling =
                snapshot(18.0, 50.0, 15.0, 26.0, 58.0, 21.0);

        float reference = seenPercent(straddling, cx, cy, cz, view, REFERENCE);
        float dispatcher = seenPercent(straddling, cx, cy, cz, view, DISPATCHER);
        assertThat(dispatcher).as("mixed in/out-of-bounds seenPercent must equal reference").isEqualTo(reference);
    }

    /**
     * Verbatim replica of {@code ExplosionExposureComputer.getSeenPercentFromFlatView}'s
     * sampling loop, parameterized by the ray function, so the production
     * exposure can be compared across the boxed, reference, and dispatcher paths.
     */
    private static float seenPercent(ExplosionEntityDamageComputer.EntityDamageSnapshot snapshot,
                                     double centerX, double centerY, double centerZ,
                                     WorldReadViewImpl view, RayTracer ray) {
        double minX = snapshot.minX(), maxX = snapshot.maxX();
        double minY = snapshot.minY(), maxY = snapshot.maxY();
        double minZ = snapshot.minZ(), maxZ = snapshot.maxZ();
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
                    // Production dispatcher applies the vanilla traverseBlocks
                    // backoff before either implementation runs; emulate that
                    // here so all paths compare on the same cell sequence.
                    double[] b = ExplosionRayCast.backoffEndpoints(
                            sx, sy, sz, centerX, centerY, centerZ);
                    if (!ray.hit(b[0], b[1], b[2], b[3], b[4], b[5], view)) hits++;
                    count++;
                }
            }
        }
        return (float) hits / count;
    }

    /** A one-row snapshot holding just a box, for the exposure comparisons. */
    private static ExplosionEntityDamageComputer.EntityDamageSnapshot snapshot(
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return new ExplosionEntityDamageComputer.EntityDamageSnapshot(
                42, 0.0, 0.0, 0.0, 0.0,
                minX, minY, minZ, maxX, maxY, maxZ,
                false, 0.0F, 0.0F, false, 0.0);
    }

    // ── Multi-box shapes: exact per-box test vs bounding-box approximation ──

    /** Builds a view with one bottom-half oak stair at (20, 60, 30): the
     *  collision shape is the bottom slab plus a half-footprint upper step — a
     *  genuine multi-box shape (a fence's collision shape merges to one AABB in
     *  this version, so it cannot pin the per-box path). */
    private static WorldReadViewImpl buildStairsView() {
        BlockState[] states = new BlockState[STRIDE_Z * (MAX_Z - MIN_Z + 1)];
        Arrays.fill(states, Blocks.AIR.defaultBlockState());
        setBlock(states, 20, 60, 30, Blocks.OAK_STAIRS.defaultBlockState());
        double[][] boxes = ExplosionShapeBoxes.flattenShapeBoxes(
                states, TEST_LEVEL, MIN_X, MIN_Y, MIN_Z, STRIDE_Y, STRIDE_Z, states.length);
        return new WorldReadViewImpl(states, boxes,
                MIN_X, MIN_Y, MIN_Z, MAX_X, MAX_Y, MAX_Z, STRIDE_Y, STRIDE_Z);
    }

    /**
     * A bottom stair is a multi-box shape (full-footprint bottom slab + a
     * half-footprint upper step). Vanilla's clip tests the exact per-box
     * decomposition, so rays that pass over the upper step's missing half must
     * miss, and rays through either box must hit; the precomputed per-box path
     * must agree on every ray.
     */
    @Test
    void stairs_multiBoxMatchesVanillaClip() {
        WorldReadViewImpl view = buildStairsView();
        BlockPos stairsPos = new BlockPos(20, 60, 30);
        VoxelShape stairsShape = view.getBlockState(20, 60, 30)
                .getCollisionShape(TEST_LEVEL, stairsPos);
        assertThat(stairsShape.toAabbs()).as("fixture must be a real multi-box shape").hasSizeGreaterThan(1);

        double[][] rays = {
                {19.0, 60.25, 30.5, 21.0, 60.25, 30.5},   // through the bottom slab
                {19.0, 60.75, 30.5, 21.0, 60.75, 30.5},   // upper step, along its long axis
                {20.5, 60.75, 29.0, 20.5, 60.75, 31.0},   // upper step, along its short axis
                {20.5, 60.95, 29.0, 20.5, 60.95, 31.0},   // above the step's top half
                {19.0, 60.5, 29.5, 21.0, 60.5, 29.5},     // hugging the step/slab seam
        };

        for (double[] r : rays) {
            net.minecraft.world.phys.Vec3 from = new net.minecraft.world.phys.Vec3(r[0], r[1], r[2]);
            net.minecraft.world.phys.Vec3 to = new net.minecraft.world.phys.Vec3(r[3], r[4], r[5]);
            net.minecraft.world.phys.BlockHitResult vanillaHit = stairsShape.clip(from, to, stairsPos);
            boolean flatHit = ExplosionRayCast.rayIntersectsBlockFlatBoxed(
                    r[0], r[1], r[2], r[3], r[4], r[5], view);
            assertThat(flatHit)
                    .as("flat per-box path must agree with vanilla clip for ray " + Arrays.toString(r))
                    .isEqualTo(vanillaHit != null);
        }
    }

    // ── Test-only reference DDA ──

    /**
     * Independent reference implementation: walks the flat view cell by cell and
     * resolves each non-air cell's collision shape directly through
     * {@code getCollisionShape(TEST_LEVEL, pos)}. Only valid for non-dynamic
     * states (the fixture is static); production uses the precomputed box table
     * so workers never touch a level or block-shape API.
     */
    private static boolean referenceRay(double fx, double fy, double fz,
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

        while (true) {
            if (stepX > 0 ? x > endX : (stepX < 0 ? x < endX : false)) break;
            if (stepY > 0 ? y > endY : (stepY < 0 ? y < endY : false)) break;
            if (stepZ > 0 ? z > endZ : (stepZ < 0 ? z < endZ : false)) break;

            BlockState state = worldView.getBlockState(x, y, z);
            if (!state.isAir()) {
                if (state.getBlock().hasDynamicShape()) {
                    throw new AssertionError("reference DDA is only valid for static shapes: " + state);
                }
                VoxelShape shape = state.getCollisionShape(TEST_LEVEL, REFERENCE_POS.set(x, y, z));
                if (shape == net.minecraft.world.phys.shapes.Shapes.block()) {
                    if (rayAabbIntersectsFlat(fx, fy, fz, tx, ty, tz, x, y, z, x + 1.0, y + 1.0, z + 1.0))
                        return true;
                } else if (!shape.isEmpty()) {
                    for (net.minecraft.world.phys.AABB bb : shape.toAabbs()) {
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
