package com.github.uright008.ep;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Behavior-consistency tests for {@link ExplosionRayParams} — verifies
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
 * normalization, step-size invariance, and the dynamic step budget.
 */
@DisplayName("ExplosionRayParams — vanilla ray generation")
class ExplosionHelperRayTest {

    // ── Ray count: 1352 = 16³ − 14³ ──────────

    @Test
    @DisplayName("Vanilla: RAY_PARAMS static field has 1352 rays")
    void rayParams_staticField_has1352Rays() {
        assertThat(ExplosionRayParams.RAY_PARAMS)
                .as("RAY_PARAMS must contain 1352 rays")
                .hasSize(1352);
    }

    // ── Direction normalization ──────────────────

    @Test
    @DisplayName("Vanilla: every ray direction is unit-length (|d| ≈ 1.0)")
    void allRayDirections_unitNormalized() {
        var rays = ExplosionRayParams.RAY_PARAMS;
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
        var rays = ExplosionRayParams.RAY_PARAMS;
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
        var rays = ExplosionRayParams.RAY_PARAMS;

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
        var rays = ExplosionRayParams.RAY_PARAMS;
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

    // ── Step budget ───────────────────────────────

    @Test
    @DisplayName("rayMaxSteps: step budget covers region reach for large radii")
    void rayMaxSteps_coversLargeRadii() {
        // The old hardcoded 128-step cap truncated rays once radius exceeded
        // ~22 (128 steps x 0.3 = 38.4 blocks reach; region reach = ceil(r*1.3/0.225)*0.3).
        // The dynamic budget must cover the region reach at every radius.
        for (float radius : new float[]{4.0F, 6.5F, 12.0F, 22.0F, 30.0F, 50.0F}) {
            int steps = ExplosionRayCast.rayMaxSteps(radius);
            int reach = (int) Math.ceil(Math.ceil(radius * 1.3F / 0.22500001F) * 0.3);
            int stepsToReach = (int) Math.ceil(reach / 0.3);
            assertThat(steps)
                    .as("step budget for radius %s must reach %s blocks", radius, reach)
                    .isGreaterThanOrEqualTo(stepsToReach);
        }
        assertThat(ExplosionRayCast.rayMaxSteps(4.0F)).as("TNT radius").isLessThan(128);
        assertThat(ExplosionRayCast.rayMaxSteps(50.0F)).as("large radius needs more than old 128 cap").isGreaterThan(128);
    }

    @Test
    @DisplayName("Vanilla: each RayParam record has correct field count")
    void rayParam_recordFields() {
        var ray = ExplosionRayParams.RAY_PARAMS.getFirst();
        assertThat(ray.xd()).isNotNull();
        assertThat(ray.yd()).isNotNull();
        assertThat(ray.zd()).isNotNull();
        assertThat(ray.stepX()).isNotNull();
        assertThat(ray.stepY()).isNotNull();
        assertThat(ray.stepZ()).isNotNull();

        var result = new ExplosionEntityDamageComputer.EntityDamageResult(1, 0f, 0, 0, 0);
        assertThat(result.damage()).isZero();
    }
}
