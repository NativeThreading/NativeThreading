package com.github.uright008.ep;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Differential test for the explosion flat-view ray paths in
 * {@link ExplosionHelper}.
 *
 * <p>{@link ExplosionHelper#rayIntersectsBlockFlatFast} walks the raw
 * {@code states[]} array with incremental index updates and must return
 * bit-identical booleans to the reference DDA
 * ({@code rayIntersectsBlockFlatSlow}) and to the dispatcher
 * ({@code rayIntersectsBlockFlat}) for every ray — in-bounds (fast path),
 * out-of-bounds (slow fallback), zero-length, boundary-touching, axis-aligned,
 * and 3D-diagonal rays.</p>
 */
class ExplosionRayFlatDifferentialTest {

    private static final int MIN_X = 10, MIN_Y = 40, MIN_Z = 20;
    private static final int MAX_X = 40, MAX_Y = 80, MAX_Z = 60;
    private static final int STRIDE_Y = MAX_X - MIN_X + 1;
    private static final int STRIDE_Z = STRIDE_Y * (MAX_Y - MIN_Y + 1);

    /** Ray function over the flat view; switches between slow/fast/dispatcher. */
    @FunctionalInterface
    private interface RayTracer {
        boolean hit(double fx, double fy, double fz,
                    double tx, double ty, double tz, WorldReadViewImpl view);
    }

    private static final RayTracer SLOW = ExplosionHelper::rayIntersectsBlockFlatSlow;
    private static final RayTracer FAST = ExplosionHelper::rayIntersectsBlockFlatFast;
    private static final RayTracer DISPATCHER = ExplosionHelper::rayIntersectsBlockFlat;

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

        return new WorldReadViewImpl(states, MIN_X, MIN_Y, MIN_Z, MAX_X, MAX_Y, MAX_Z, STRIDE_Y, STRIDE_Z);
    }

    /** Like {@link #buildView()} but with the production precomputed shapes array. */
    private static WorldReadViewImpl buildViewWithShapes() {
        WorldReadViewImpl view = buildView();
        BlockState[] states = view.states();
        net.minecraft.world.phys.shapes.VoxelShape[] shapes = new net.minecraft.world.phys.shapes.VoxelShape[states.length];
        for (int i = 0; i < states.length; i++) {
            BlockState s = states[i];
            shapes[i] = s.isAir() ? null : s.getCollisionShape(null, null);
        }
        return new WorldReadViewImpl(states, shapes, MIN_X, MIN_Y, MIN_Z, MAX_X, MAX_Y, MAX_Z, STRIDE_Y, STRIDE_Z);
    }

    // ── Per-ray differential: fast == slow == dispatcher ──

    @Test
    void rayDifferential_fastEqualsSlowEqualsDispatcher() {
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
                // Outside the view — must fall back to the slow path.
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

    @Test
    void rayDifferential_precomputedShapes_matchLivePath() {
        WorldReadViewImpl view = buildViewWithShapes();
        double cx = 25.0, cy = 60.0, cz = 40.0;

        double[][] starts = {
                {22.0, 62.0, 42.0}, {15.5, 55.0, 55.0}, {31.0, 45.5, 26.0},
                {19.5, 60.0, 30.0}, {25.0, 60.0, 59.5}, {25.0, 41.5, 40.0},
                {10.0, 60.0, 40.0}, {40.0, 60.0, 40.0}, {10.5, 40.5, 20.5},
                {39.5, 79.5, 59.5}, {0.0, 0.0, 0.0}, {50.0, 90.0, 70.0},
        };
        for (double[] s : starts) {
            assertRayMatchesAll(view, s[0], s[1], s[2], cx, cy, cz);
            assertRayMatchesAll(view, cx, cy, cz, s[0], s[1], s[2]);
        }
        assertRayMatchesAll(view, 10.0, 40.0, 20.0, 40.0, 80.0, 60.0);
        assertRayMatchesAll(view, 32.5, 46.5, 26.5, 32.5, 43.5, 26.5);
    }

    private static void assertRayMatchesAll(WorldReadViewImpl view,
                                            double fx, double fy, double fz,
                                            double tx, double ty, double tz) {
        boolean slow = SLOW.hit(fx, fy, fz, tx, ty, tz, view);
        boolean dispatcher = DISPATCHER.hit(fx, fy, fz, tx, ty, tz, view);
        String ray = String.format("ray (%s,%s,%s)->(%s,%s,%s)", fx, fy, fz, tx, ty, tz);
        assertThat(dispatcher).as(ray + " dispatcher").isEqualTo(slow);
        // The raw fast path is only defined inside the view bounds; the
        // dispatcher routes to it exactly when rayWithinBounds holds.
        if (ExplosionHelper.rayWithinBounds(view, fx, fy, fz, tx, ty, tz)) {
            boolean fast = FAST.hit(fx, fy, fz, tx, ty, tz, view);
            assertThat(fast).as(ray + " fast path").isEqualTo(slow);
        }
    }

    // ── Behavior pins: the fast path must actually block / not block ──

    @Test
    void behaviorPins_blockingAndOpenRays() {
        WorldReadViewImpl view = buildView();

        // Crosses the stone wall (z=55 plane) — must hit.
        assertThat(FAST.hit(12.5, 50.5, 55.5, 24.5, 70.5, 55.5, view)).isTrue();
        // Hits the single stone at (20,60,30).
        assertThat(FAST.hit(20.5, 59.5, 30.5, 19.5, 60.5, 30.5, view)).isTrue();
        // Open-air short ray — must not hit.
        assertThat(FAST.hit(12.5, 42.5, 22.5, 14.5, 44.5, 24.5, view)).isFalse();
        // Partial slab (non-full VoxelShape): with the relative-bounds fix the
        // ray from y=46.5 down through the slab's lower half must hit in both paths.
        assertThat(FAST.hit(32.5, 46.5, 26.5, 32.5, 43.5, 26.5, view)).isTrue();

        // Slow agrees on the same rays.
        assertThat(SLOW.hit(12.5, 50.5, 55.5, 24.5, 70.5, 55.5, view)).isTrue();
        assertThat(SLOW.hit(20.5, 59.5, 30.5, 19.5, 60.5, 30.5, view)).isTrue();
        assertThat(SLOW.hit(12.5, 42.5, 22.5, 14.5, 44.5, 24.5, view)).isFalse();
        assertThat(SLOW.hit(32.5, 46.5, 26.5, 32.5, 43.5, 26.5, view)).isTrue();
    }

    // ── getSeenPercent hit-count / fraction differential ──

    @Test
    void seenPercent_fastSlowDispatcher_bitIdentical() {
        WorldReadViewImpl view = buildView();
        double cx = 20.0, cy = 60.0, cz = 55.0; // center on the wall plane

        // Entity box entirely inside the view, on the near side of the wall.
        ExplosionHelper.EntityDamageSnapshot inBounds =
                snapshot(16.0, 55.0, 48.0, 24.0, 63.0, 54.0);

        float slow = seenPercent(inBounds, cx, cy, cz, view, SLOW);
        float fast = seenPercent(inBounds, cx, cy, cz, view, FAST);
        float dispatcher = seenPercent(inBounds, cx, cy, cz, view, DISPATCHER);
        assertThat(fast).as("fast seenPercent must equal slow").isEqualTo(slow);
        assertThat(dispatcher).as("dispatcher seenPercent must equal slow").isEqualTo(slow);

        // Production getSeenPercentFromFlatView goes through the dispatcher; the
        // resulting damage/knockback must equal a slow-only exposure end to end.
        ExplosionHelper.EntityDamageSnapshot damageSnapshot = new ExplosionHelper.EntityDamageSnapshot(
                42,
                19.0, 55.5, 49.5, 60.0,
                16.0, 55.0, 48.0, 24.0, 63.0, 54.0,
                true, 1.0F, 0.0F, false, 0.0);
        ExplosionHelper.EntityDamageResult viaView = ExplosionHelper.computeEntityDamage(
                damageSnapshot, cx, cy, cz, 32.0F, view);
        ExplosionHelper.EntityDamageSnapshot withSlowExposure = new ExplosionHelper.EntityDamageSnapshot(
                42,
                19.0, 55.5, 49.5, 60.0,
                16.0, 55.0, 48.0, 24.0, 63.0, 54.0,
                true, 1.0F, slow, true, 0.0);
        ExplosionHelper.EntityDamageResult viaSlowExposure = ExplosionHelper.computeEntityDamage(
                withSlowExposure, cx, cy, cz, 32.0F, view);
        assertThat(viaView.damage()).isEqualTo(viaSlowExposure.damage());
        assertThat(viaView.kbX()).isEqualTo(viaSlowExposure.kbX());
        assertThat(viaView.kbY()).isEqualTo(viaSlowExposure.kbY());
        assertThat(viaView.kbZ()).isEqualTo(viaSlowExposure.kbZ());
    }

    @Test
    void seenPercent_dispatcherMixesFastAndSlow_bitIdentical() {
        WorldReadViewImpl view = buildView();
        double cx = 25.0, cy = 60.0, cz = 40.0;

        // Box straddles the view's minZ=20 plane: near-side samples fall back to
        // slow, far-side samples take the fast path. The mixed fraction must
        // equal the all-slow fraction.
        ExplosionHelper.EntityDamageSnapshot straddling =
                snapshot(18.0, 50.0, 15.0, 26.0, 58.0, 21.0);

        float slow = seenPercent(straddling, cx, cy, cz, view, SLOW);
        float dispatcher = seenPercent(straddling, cx, cy, cz, view, DISPATCHER);
        assertThat(dispatcher).as("mixed fast/slow seenPercent must equal slow").isEqualTo(slow);
    }

    /**
     * Verbatim replica of {@code ExplosionHelper.getSeenPercentFromFlatView}'s
     * sampling loop, parameterized by the ray function, so the production
     * exposure can be compared across the fast, slow, and dispatcher paths.
     */
    private static float seenPercent(ExplosionHelper.EntityDamageSnapshot snapshot,
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
                    if (!ray.hit(sx, sy, sz, centerX, centerY, centerZ, view)) hits++;
                    count++;
                }
            }
        }
        return (float) hits / count;
    }

    private static ExplosionHelper.EntityDamageSnapshot snapshot(
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return new ExplosionHelper.EntityDamageSnapshot(
                42, 0.0, 0.0, 0.0, 0.0,
                minX, minY, minZ, maxX, maxY, maxZ,
                false, 0.0F, 0.0F, false, 0.0);
    }

    // ── Multi-box shapes: exact per-box test vs bounding-box approximation ──

    /** Builds a view with a single oak fence post at (20, 60, 30). */
    private static WorldReadViewImpl buildFenceView() {
        BlockState[] states = new BlockState[STRIDE_Z * (MAX_Z - MIN_Z + 1)];
        Arrays.fill(states, Blocks.AIR.defaultBlockState());
        setBlock(states, 20, 60, 30, Blocks.OAK_FENCE.defaultBlockState());
        net.minecraft.world.phys.shapes.VoxelShape[] shapes =
                new net.minecraft.world.phys.shapes.VoxelShape[states.length];
        for (int i = 0; i < states.length; i++) {
            BlockState s = states[i];
            shapes[i] = s.isAir() ? null : s.getCollisionShape(null, null);
        }
        double[][] boxes = ExplosionHelper.flattenShapeBoxes(states, shapes, states.length);
        return new WorldReadViewImpl(states, shapes, boxes,
                MIN_X, MIN_Y, MIN_Z, MAX_X, MAX_Y, MAX_Z, STRIDE_Y, STRIDE_Z);
    }

    /**
     * A fence is a multi-box shape (posts + rails). A ray passing through the
     * fence gap (e.g. at eye height between rails) must NOT hit — vanilla's
     * clip tests the exact per-box decomposition. The precomputed per-box path
     * must agree with vanilla clip on the same rays.
     */
    @Test
    void fenceGap_multiBoxMatchesVanillaClip() {
        WorldReadViewImpl view = buildFenceView();

        // Fence center at x=20, z=30. Its horizontal rails sit in the lower and
        // upper thirds; the mid-gap ray passes through the opening.
        // Ray from west of the fence to east, at a height through the gap:
        double gapY = 60.5;
        double fx = 19.0, fz = 30.5;
        double tx = 21.0, tz = 30.5;

        // Vanilla clip semantics: does the ray hit the fence's exact shape?
        net.minecraft.world.phys.Vec3 from = new net.minecraft.world.phys.Vec3(fx, gapY, fz);
        net.minecraft.world.phys.Vec3 to = new net.minecraft.world.phys.Vec3(tx, gapY, tz);
        net.minecraft.world.phys.BlockHitResult vanillaHit =
                view.shapes()[index(20, 60, 30)].clip(from, to, new BlockPos(20, 60, 30));

        // NT fast path with precomputed per-box shapes.
        boolean flatHit = ExplosionHelper.rayIntersectsBlockFlatFast(
                fx, gapY, fz, tx, gapY, tz, view);

        assertThat(flatHit).as("flat per-box path must agree with vanilla clip").isEqualTo(vanillaHit != null);
    }
}
