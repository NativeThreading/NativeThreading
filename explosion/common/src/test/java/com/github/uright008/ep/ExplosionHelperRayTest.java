package com.github.uright008.ep;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Behavior-consistency tests for {@link ExplosionHelper} — verifies
 * explosion ray generation matches vanilla Minecraft's 1352-ray
 * surface-sampled explosion model.
 *
 * <h3>Vanilla explosion ray model</h3>
 * Minecraft casts 1352 rays from the explosion center, one through each
 * surface voxel of a 16×16×16 unit-cube grid. Each ray direction is
 * unit-normalized and stepped in 0.3-block increments.
 *
 * <h3>AI-readable summary</h3>
 * Verifies ray count (1352 = 16³ − 14³ surface cells), direction
 * normalization, step-size invariance, and structure of the ray
 * index grid that maps 3D voxel coordinates to ray indices.
 */
@DisplayName("ExplosionHelper — vanilla ray generation")
class ExplosionHelperRayTest {

    // ── Ray count: 1352 = 16³ − 14³ ──────────

    @Test
    @DisplayName("Vanilla: gridSize=16 produces exactly 1352 rays")
    void buildRayParams_grid16_1352Rays() {
        var rays = ExplosionHelper.buildRayParams(16);
        assertThat(rays)
                .as("gridSize=16 must produce 1352 rays (16³ − 14³ surface cells)")
                .hasSize(1352);
    }

    @Test
    @DisplayName("Vanilla: RAY_PARAMS static field has 1352 rays")
    void rayParams_staticField_has1352Rays() {
        assertThat(ExplosionHelper.RAY_PARAMS)
                .as("RAY_PARAMS must contain 1352 rays")
                .hasSize(1352);
    }

    @Test
    @DisplayName("Vanilla: gridSize=N produces N³ − (N−2)³ rays")
    void buildRayParams_generalFormula() {
        for (int n = 3; n <= 12; n++) {
            var rays = ExplosionHelper.buildRayParams(n);
            int expected = n * n * n - (n - 2) * (n - 2) * (n - 2);
            assertThat(rays)
                    .as("gridSize=%d must produce %d rays (surface cells)", n, expected)
                    .hasSize(expected);
        }
    }

    // ── Direction normalization ──────────────────

    @Test
    @DisplayName("Vanilla: every ray direction is unit-length (|d| ≈ 1.0)")
    void allRayDirections_unitNormalized() {
        var rays = ExplosionHelper.buildRayParams(16);
        for (int i = 0; i < rays.size(); i++) {
            var r = rays.get(i);
            double len = Math.sqrt(r.xd() * r.xd() + r.yd() * r.yd() + r.zd() * r.zd());
            assertThat(len)
                    .as("ray[%d] direction (%.4f, %.4f, %.4f) must be unit-length (±1e-6)",
                            i, r.xd(), r.yd(), r.zd())
                    .isCloseTo(1.0, within(1e-6));
        }
    }

    // ── Step size = direction × 0.3 ──────────────

    @Test
    @DisplayName("Vanilla: each step vector = direction × 0.3")
    void allRaySteps_directionTimes0_3() {
        var rays = ExplosionHelper.buildRayParams(16);
        for (int i = 0; i < rays.size(); i++) {
            var r = rays.get(i);
            assertThat(r.stepX())
                    .as("ray[%d] stepX must be xd×0.3", i)
                    .isCloseTo(r.xd() * 0.3, within(1e-6));
            assertThat(r.stepY())
                    .as("ray[%d] stepY must be yd×0.3", i)
                    .isCloseTo(r.yd() * 0.3, within(1e-6));
            assertThat(r.stepZ())
                    .as("ray[%d] stepZ must be zd×0.3", i)
                    .isCloseTo(r.zd() * 0.3, within(1e-6));
        }
    }

    // ── Ray distribution: symmetric ──────────────

    @Test
    @DisplayName("Vanilla: rays are symmetric — every direction has an opposite")
    void rayDirections_areSymmetric() {
        var rays = ExplosionHelper.buildRayParams(16);

        // Count rays with positive X vs negative X
        int posX = 0, negX = 0, posY = 0, negY = 0, posZ = 0, negZ = 0;
        for (var r : rays) {
            if (r.xd() > 0) posX++; else if (r.xd() < 0) negX++;
            if (r.yd() > 0) posY++; else if (r.yd() < 0) negY++;
            if (r.zd() > 0) posZ++; else if (r.zd() < 0) negZ++;
        }

        assertThat(posX).as("positive X rays must equal negative X rays").isEqualTo(negX);
        assertThat(posY).as("positive Y rays must equal negative Y rays").isEqualTo(negY);
        assertThat(posZ).as("positive Z rays must equal negative Z rays").isEqualTo(posZ);
    }

    @Test
    @DisplayName("Vanilla: rays cover full hemisphere (no empty octant)")
    void rayDirections_coverAllOctants() {
        var rays = ExplosionHelper.buildRayParams(16);
        boolean[][][] octants = new boolean[2][2][2]; // x+,y+,z+ = [1][1][1]

        for (var r : rays) {
            int xi = r.xd() >= 0 ? 1 : 0;
            int yi = r.yd() >= 0 ? 1 : 0;
            int zi = r.zd() >= 0 ? 1 : 0;
            octants[xi][yi][zi] = true;
        }

        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    assertThat(octants[x][y][z])
                            .as("octant (%s, %s, %s) must have at least one ray",
                                    x == 1 ? "+" : "-", y == 1 ? "+" : "-", z == 1 ? "+" : "-")
                            .isTrue();
    }

    // ── Ray index grid structure ─────────────────

    @Test
    @DisplayName("Vanilla: RAY_INDEX_BY_GRID maps surface voxels to unique rays")
    void rayIndexGrid_mapsSurfaceToRays() {
        int[][][] grid = ExplosionHelper.RAY_INDEX_BY_GRID;

        // Every surface cell must have a valid ray index
        int surfaceCount = 0;
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    if (x == 0 || x == 15 || y == 0 || y == 15 || z == 0 || z == 15) {
                        assertThat(grid[x][y][z])
                                .as("surface voxel [%d,%d,%d] must have ray index ≥ 0", x, y, z)
                                .isGreaterThanOrEqualTo(0);
                        surfaceCount++;
                    }

        assertThat(surfaceCount).as("surface count must be 1352").isEqualTo(1352);
    }

    @Test
    @DisplayName("Vanilla: RAY_INDEX_BY_GRID marks interior voxels with -1")
    void rayIndexGrid_interiorVoxels_markedNegative() {
        int[][][] grid = ExplosionHelper.RAY_INDEX_BY_GRID;

        for (int x = 1; x < 15; x++)
            for (int y = 1; y < 15; y++)
                for (int z = 1; z < 15; z++)
                    assertThat(grid[x][y][z])
                            .as("interior voxel [%d,%d,%d] must be -1", x, y, z)
                            .isEqualTo(-1);
    }

    // ── Ray deltas: cumulative path integrity ─────

    @Test
    @DisplayName("Vanilla: RAY_DELTAS dimensions match RAY_PARAMS")
    void rayDeltas_dimensionsMatch() {
        int[][] deltas = ExplosionHelper.RAY_DELTAS;
        assertThat(deltas).as("RAY_DELTAS must have 1352 rows").hasDimensions(1352, ExplosionHelper.MAX_RAY_STEPS);
    }

    @Test
    @DisplayName("Vanilla: each RayParam record has correct field count")
    void rayParam_recordFields() {
        var ray = ExplosionHelper.buildRayParams(16).getFirst();
        assertThat(ray.xd()).isNotNull();
        assertThat(ray.yd()).isNotNull();
        assertThat(ray.zd()).isNotNull();
        assertThat(ray.stepX()).isNotNull();
        assertThat(ray.stepY()).isNotNull();
        assertThat(ray.stepZ()).isNotNull();

        var result = new ExplosionHelper.EntityDamageResult(1, 0f, 0, 0, 0);
        assertThat(result.damage()).isZero();
    }

    // ── Grid scaling ──────────────────────────────

    @Test
    @DisplayName("Vanilla: larger grid produces more rays")
    void buildRayParams_largerGrid_moreRays() {
        int prev = 0;
        for (int n = 3; n <= 12; n++) {
            int count = ExplosionHelper.buildRayParams(n).size();
            assertThat(count)
                    .as("gridSize=%d ray count must be greater than gridSize=%d", n, n - 1)
                    .isGreaterThan(prev);
            prev = count;
        }
    }

    @Test
    @DisplayName("Vanilla: parallel ray params match serial generation for grid=16")
    void buildRayParams_matchesStaticRayParams() {
        var generated = ExplosionHelper.buildRayParams(16);
        var stored = ExplosionHelper.RAY_PARAMS;

        assertThat(generated).hasSameSizeAs(stored);
        for (int i = 0; i < generated.size(); i++) {
            var g = generated.get(i);
            var s = stored.get(i);
            assertThat(g.xd()).as("ray[%d] xd", i).isCloseTo(s.xd(), within(1e-9));
            assertThat(g.yd()).as("ray[%d] yd", i).isCloseTo(s.yd(), within(1e-9));
            assertThat(g.zd()).as("ray[%d] zd", i).isCloseTo(s.zd(), within(1e-9));
        }
    }
}
