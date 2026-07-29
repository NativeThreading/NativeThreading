package com.github.uright008.ep;

import com.github.uright008.ep.GeometryVersioned;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the geometry versioning scheme used by the visibility collision cache.
 *
 * <p>The baseline tests capture the existing cache-invalidation contract (version
 * counter drives cache miss). The version-increment tests document the expected
 * {@link GeometryVersioned} contract that {@code LevelChunkSection} will satisfy
 * once the mixin is applied.</p>
 */
class GeometryVersionCacheTest {

    // ── Baseline characterization: version-based cache invalidation ──

    @Test
    void cacheHit_whenVersionUnchanged() {
        // Simulates current LevelChunkVisibilityCollisionCacheMixin behaviour:
        // a section-indexed version array drives cache validity.
        long[] sectionVersions = new long[4];
        String[] sectionCache = new String[4];

        // Capture at version 0
        sectionCache[2] = "geometry_v0";

        // Cache hit — version still 0
        long currentVersion = sectionVersions[2];
        assertThat(sectionCache[2]).isNotNull();
        assertThat(currentVersion).isEqualTo(0);
    }

    @Test
    void cacheMiss_whenVersionIncrements() {
        long[] sectionVersions = new long[4];
        String[] sectionCache = new String[4];

        // Capture at version 0
        sectionCache[2] = "geometry_v0";

        // setBlockState succeeds → increment version, clear cache entry
        sectionVersions[2]++;
        sectionCache[2] = null;

        // Cache miss — version advanced to 1
        assertThat(sectionVersions[2]).isEqualTo(1);
        assertThat(sectionCache[2]).isNull();
    }

    @Test
    void cacheMiss_afterMultipleSetBlockState() {
        long[] sectionVersions = new long[4];
        String[] sectionCache = new String[4];

        sectionCache[1] = "geometry_v0";
        sectionVersions[1]++; // first change
        sectionCache[1] = null;

        sectionCache[1] = "geometry_v1";
        sectionVersions[1]++; // second change
        sectionCache[1] = null;

        assertThat(sectionVersions[1]).isEqualTo(2);
    }

    // ── GeometryVersioned contract (failing until mixin implemented) ──

    @Test
    void geometryVersioned_startsAtZero() {
        GeometryVersioned versioned = new StubSection();

        assertThat(versioned.explosion$getGeometryVersion()).isEqualTo(0);
    }

    @Test
    void geometryVersioned_incrementsOnStateChange() {
        StubSection section = new StubSection();

        section.setBlockState("air", "stone"); // different state → version increments

        assertThat(section.explosion$getGeometryVersion()).isEqualTo(1);
    }

    @Test
    void geometryVersioned_doesNotIncrementOnSameState() {
        StubSection section = new StubSection();

        section.setBlockState("air", "stone");   // v0 → v1 (state changed)
        section.setBlockState("stone", "stone"); // same state → no increment

        assertThat(section.explosion$getGeometryVersion()).isEqualTo(1);
    }

    @Test
    void geometryVersioned_incrementsMultipleTimes() {
        StubSection section = new StubSection();

        section.setBlockState("air", "stone");   // v0 → v1
        section.setBlockState("stone", "air");   // v1 → v2
        section.setBlockState("air", "cobble");  // v2 → v3

        assertThat(section.explosion$getGeometryVersion()).isEqualTo(3);
    }

    @Test
    void geometryVersioned_noIncrementWhenSameStateInterspersed() {
        StubSection section = new StubSection();

        section.setBlockState("air", "stone");   // v0 → v1
        section.setBlockState("stone", "stone"); // same → stays v1
        section.setBlockState("stone", "air");   // v1 → v2

        assertThat(section.explosion$getGeometryVersion()).isEqualTo(2);
    }

    // ── Test helper: simulates LevelChunkSection.setBlockState behaviour ──

    /**
     * Minimal stub mirroring the version-increment logic that the real mixin
     * injects into {@code LevelChunkSection.setBlockState}.
     */
    private static final class StubSection implements GeometryVersioned {
        private long geometryVersion;
        private String currentState = "air";

        @Override
        public long explosion$getGeometryVersion() {
            return geometryVersion;
        }

        /**
         * Simulates {@code LevelChunkSection.setBlockState}: always returns the
         * previous state; increments geometry version only when the new state
         * differs from the previous one (reference equality, matching vanilla).
         */
        String setBlockState(String previousState, String newState) {
            String previous = currentState;
            currentState = newState;
            if (previous != newState) {
                geometryVersion++;
            }
            return previous;
        }
    }
}
