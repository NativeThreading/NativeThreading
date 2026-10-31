package com.github.uright008.ep;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Differential test for the clear-region exposure fast path in
 * {@link ExplosionExposureComputer}: when the conservative envelope around the
 * entity AABB, the vanilla sample offsets and the blast center contains no
 * collision boxes, the fast path returns {@code 1.0F}; otherwise the exact
 * per-ray walk must produce the same value as an independent reference
 * implementation of vanilla's {@code getSeenPercent} sampling.
 */
class ExplosionExposureClearRegionTest {

    private static final int MIN_X = 0, MIN_Y = 0, MIN_Z = 0;
    private static final int MAX_X = 15, MAX_Y = 15, MAX_Z = 15;
    private static final int STRIDE_Y = MAX_X - MIN_X + 1;
    private static final int STRIDE_Z = STRIDE_Y * (MAX_Y - MIN_Y + 1);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static int index(int x, int y, int z) {
        return (x - MIN_X) + (y - MIN_Y) * STRIDE_Y + (z - MIN_Z) * STRIDE_Z;
    }

    private static WorldReadViewImpl view(BlockState[] states) {
        double[][] boxes = ExplosionShapeBoxes.flattenShapeBoxes(
                states, EmptyBlockGetter.INSTANCE, MIN_X, MIN_Y, MIN_Z,
                STRIDE_Y, STRIDE_Z, states.length);
        return new WorldReadViewImpl(states, boxes,
                MIN_X, MIN_Y, MIN_Z, MAX_X, MAX_Y, MAX_Z, STRIDE_Y, STRIDE_Z);
    }

    private static BlockState[] airStates() {
        BlockState[] states = new BlockState[STRIDE_Z * (MAX_Z - MIN_Z + 1)];
        Arrays.fill(states, Blocks.AIR.defaultBlockState());
        return states;
    }

    private static ExplosionEntityDamageComputer.EntityDamageSnapshot snapshot(
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return new ExplosionEntityDamageComputer.EntityDamageSnapshot(
                1,
                minX, minY, minZ, maxY,
                minX, minY, minZ, maxX, maxY, maxZ,
                true, 1.0F, 0.0F, false, 0.0);
    }

    /** Independent reference: the vanilla sampling loop over the boxed ray walk. */
    private static float referenceExposure(ExplosionEntityDamageComputer.EntityDamageSnapshot snapshot,
                                           double centerX, double centerY, double centerZ,
                                           WorldReadViewImpl view) {
        double minX = snapshot.minX(), maxX = snapshot.maxX();
        double minY = snapshot.minY(), maxY = snapshot.maxY();
        double minZ = snapshot.minZ(), maxZ = snapshot.maxZ();
        double samplingFactor = 2.0;
        double xs = 1.0 / ((maxX - minX) * samplingFactor + 1.0);
        double ys = 1.0 / ((maxY - minY) * samplingFactor + 1.0);
        double zs = 1.0 / ((maxZ - minZ) * samplingFactor + 1.0);
        double xOffset = (1.0 - Math.floor(1.0 / xs) * xs) / 2.0;
        double zOffset = (1.0 - Math.floor(1.0 / zs) * zs) / 2.0;
        int hits = 0, count = 0;
        for (double xx = 0.0; xx <= 1.0; xx += xs) {
            for (double yy = 0.0; yy <= 1.0; yy += ys) {
                for (double zz = 0.0; zz <= 1.0; zz += zs) {
                    double sx = minX + (maxX - minX) * xx + xOffset;
                    double sy = minY + (maxY - minY) * yy;
                    double sz = minZ + (maxZ - minZ) * zz + zOffset;
                    if (!ExplosionRayCast.rayIntersectsBlockFlat(sx, sy, sz, centerX, centerY, centerZ, view)) {
                        hits++;
                    }
                    count++;
                }
            }
        }
        return (float) hits / count;
    }

    private static float exposure(ExplosionEntityDamageComputer.EntityDamageSnapshot snapshot,
                                  double centerX, double centerY, double centerZ,
                                  WorldReadViewImpl view) {
        return ExplosionExposureComputer.getSeenPercentFromFlatView(
                snapshot, centerX, centerY, centerZ, view);
    }

    @Test
    void clearAirRegionIsExactlyOne() {
        WorldReadViewImpl view = view(airStates());
        var snap = snapshot(6.0, 6.0, 6.0, 6.98, 7.48, 6.98);
        assertThat(ExplosionExposureComputer.isClearExposureRegion(
                6.0, 6.0, 6.0, 6.98, 7.48, 6.98, 8.5, 9.5, 8.5, view)).isTrue();
        assertThat(exposure(snap, 8.5, 9.5, 8.5, view)).isEqualTo(1.0F);
    }

    @Test
    void obstacleInEnvelopeFallsThroughToExactWalk() {
        BlockState[] states = airStates();
        states[index(8, 9, 8)] = Blocks.STONE.defaultBlockState();
        WorldReadViewImpl view = view(states);
        var snap = snapshot(6.0, 6.0, 6.0, 6.98, 7.48, 6.98);

        assertThat(ExplosionExposureComputer.isClearExposureRegion(
                6.0, 6.0, 6.0, 6.98, 7.48, 6.98, 8.5, 9.5, 8.5, view)).isFalse();
        assertThat(exposure(snap, 8.5, 9.5, 8.5, view))
                .isEqualTo(referenceExposure(snap, 8.5, 9.5, 8.5, view));
    }

    @Test
    void obstacleOutsideEnvelopeKeepsFastPath() {
        BlockState[] states = airStates();
        states[index(1, 1, 1)] = Blocks.STONE.defaultBlockState();
        WorldReadViewImpl view = view(states);
        var snap = snapshot(10.0, 10.0, 10.0, 10.98, 10.48, 10.98);
        assertThat(exposure(snap, 10.5, 10.5, 10.5, view)).isEqualTo(1.0F);
    }

    @Test
    void differentialAcrossSizesCentersAndObstacles() {
        BlockState[] states = airStates();
        // A slab row and a wall: some envelopes are clear, some are not.
        for (int x = 2; x <= 5; x++) {
            for (int z = 2; z <= 5; z++) states[index(x, 4, z)] = Blocks.STONE_SLAB.defaultBlockState();
        }
        for (int y = 8; y <= 12; y++) states[index(12, y, 11)] = Blocks.STONE.defaultBlockState();
        WorldReadViewImpl view = view(states);

        double[][] bbs = {
                // {minX, minY, minZ, widthX, heightY, widthZ}
                {6.0, 6.0, 6.0, 0.98, 0.98, 0.98},
                {3.0, 4.5, 3.0, 0.5, 1.8, 0.5},   // above the slab row
                {10.0, 10.0, 10.0, 1.4, 2.9, 1.4}, // tall entity near the wall
                {12.5, 8.0, 11.0, 1.0, 1.0, 1.0},  // overlapping the wall cell
                {1.0, 1.0, 1.0, 0.98, 0.98, 0.98}, // far from center
                {13.0, 12.0, 13.0, 0.3, 0.3, 0.3}, // tiny entity near corner
        };
        double[][] centers = {
                {8.5, 9.5, 8.5},
                {5.5, 5.5, 5.5},
                {11.5, 9.0, 10.5},
                {12.5, 8.5, 11.5},
                {2.5, 3.5, 2.5},
        };
        int checked = 0;
        for (double[] b : bbs) {
            var snap = snapshot(b[0], b[1], b[2], b[0] + b[3], b[1] + b[4], b[2] + b[5]);
            for (double[] c : centers) {
                float fast = exposure(snap, c[0], c[1], c[2], view);
                float reference = referenceExposure(snap, c[0], c[1], c[2], view);
                assertThat(fast)
                        .as("bb=(%s,%s,%s)+(%s,%s,%s) center=(%s,%s,%s)",
                                b[0], b[1], b[2], b[3], b[4], b[5], c[0], c[1], c[2])
                        .isEqualTo(reference);
                checked++;
            }
        }
        assertThat(checked).isEqualTo(bbs.length * centers.length);
    }

    @Test
    void largeEnvelopeSkipsPrecheckButStaysExact() {
        WorldReadViewImpl view = view(airStates());
        // 3x3x3 entity: the envelope spans far more than the precheck cell cap.
        var snap = snapshot(2.0, 2.0, 2.0, 12.0, 12.0, 12.0);
        assertThat(ExplosionExposureComputer.isClearExposureRegion(
                2.0, 2.0, 2.0, 12.0, 12.0, 12.0, 7.0, 7.0, 7.0, view)).isFalse();
        assertThat(exposure(snap, 7.0, 7.0, 7.0, view)).isEqualTo(1.0F);
    }
}
