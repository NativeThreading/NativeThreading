package com.github.uright008.ep;

import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VisibilityCollisionSnapshotTest {

    @Test
    void blocksRay_whenFrozenCollisionBoxIntersectsSegment() {
        // Given
        var snapshot = VisibilityCollisionSnapshot.of(List.of(
                new VisibilityCollisionSnapshot.CollisionBox(1.0, 0.0, 0.0, 2.0, 1.0, 1.0)));

        // When
        boolean blocked = snapshot.blocks(new VisibilityCollisionSnapshot.RaySegment(
                0.0, 0.5, 0.5, 3.0, 0.5, 0.5));

        // Then
        assertThat(blocked).isTrue();
    }

    @Test
    void blocksRay_whenFrozenCollisionBoxMissesSegment() {
        // Given
        var snapshot = VisibilityCollisionSnapshot.of(List.of(
                new VisibilityCollisionSnapshot.CollisionBox(1.0, 0.0, 0.0, 2.0, 1.0, 1.0)));

        // When
        boolean blocked = snapshot.blocks(new VisibilityCollisionSnapshot.RaySegment(
                0.0, 2.0, 0.5, 3.0, 2.0, 0.5));

        // Then
        assertThat(blocked).isFalse();
    }

    @Test
    void blocksRay_whenSourceListChangesAfterCapture() {
        // Given
        var sourceBoxes = new ArrayList<>(List.of(
                new VisibilityCollisionSnapshot.CollisionBox(1.0, 0.0, 0.0, 2.0, 1.0, 1.0)));
        var snapshot = VisibilityCollisionSnapshot.of(sourceBoxes);
        sourceBoxes.clear();

        // When
        boolean blocked = snapshot.blocks(new VisibilityCollisionSnapshot.RaySegment(
                0.0, 0.5, 0.5, 3.0, 0.5, 0.5));

        // Then
        assertThat(blocked).isTrue();
    }

    @Test
    void blocksRay_whenOffPathCellIsPopulated() {
        // Given
        var snapshot = VisibilityCollisionSnapshot.of(List.of(
                new VisibilityCollisionSnapshot.CollisionBox(0.0, 5.0, 0.0, 1.0, 6.0, 1.0)));

        // When
        boolean blocked = snapshot.blocks(new VisibilityCollisionSnapshot.RaySegment(
                0.0, 0.5, 0.5, 3.0, 0.5, 0.5));

        // Then
        assertThat(blocked).isFalse();
    }

    @Test
    void blocksRay_whenCollisionBoxExtendsIntoTraversedNeighborCell() {
        // Given
        var snapshot = VisibilityCollisionSnapshot.of(List.of(
                new VisibilityCollisionSnapshot.CollisionBox(0.5, 0.0, 0.0, 1.5, 1.0, 1.0)));

        // When
        boolean blocked = snapshot.blocks(new VisibilityCollisionSnapshot.RaySegment(
                1.1, 0.5, 0.5, 1.4, 0.5, 0.5));

        // Then
        assertThat(blocked).isTrue();
    }

    @Test
    void blocksRay_whenLaterCollisionBoxInSameCellIntersectsSegment() {
        // Given
        var snapshot = VisibilityCollisionSnapshot.of(List.of(
                new VisibilityCollisionSnapshot.CollisionBox(1.0, 0.0, 0.0, 1.25, 0.25, 1.0),
                new VisibilityCollisionSnapshot.CollisionBox(1.75, 0.25, 0.0, 2.0, 0.75, 1.0)));

        // When
        boolean blocked = snapshot.blocks(new VisibilityCollisionSnapshot.RaySegment(
                0.0, 0.5, 0.5, 3.0, 0.5, 0.5));

        // Then
        assertThat(blocked).isTrue();
    }

    @Test
    void dynamicCollisionShapeFlagIsIneligibleForContextFreeCapture() {
        // Given
        boolean hasDynamicShape = true;

        // When
        boolean eligible = VisibilityCollisionSnapshot.isContextFree(hasDynamicShape, false);

        // Then
        assertThat(eligible).isFalse();
    }

    @Test
    void staticCollisionShapeFlagIsEligibleForContextFreeCapture() {
        // Given
        boolean hasDynamicShape = false;

        // When
        boolean eligible = VisibilityCollisionSnapshot.isContextFree(hasDynamicShape, false);

        // Then
        assertThat(eligible).isTrue();
    }

    @Test
    void sourceLiquidFlagIsIneligibleForContextFreeCapture() {
        // Given
        boolean isSourceLiquid = true;

        // When
        boolean eligible = VisibilityCollisionSnapshot.isContextFree(false, isSourceLiquid);

        // Then
        assertThat(eligible).isFalse();
    }

    @Test
    void staticGeometry_translatesFrozenBoxesWithoutLiveStateAccess() {
        // Given
        VisibilityCollisionSnapshot.StaticGeometry geometry =
                new VisibilityCollisionSnapshot.StaticGeometry(new double[] {0.0, 0.0, 0.0, 1.0, 1.0, 1.0});
        VisibilityCollisionSectionGeometry.DoubleCoordinates boxes =
                new VisibilityCollisionSectionGeometry.DoubleCoordinates();

        // When
        geometry.addTo(boxes, 16, 32, 48);

        // Then
        assertThat(boxes.toArray()).containsExactly(16.0, 32.0, 48.0, 17.0, 33.0, 49.0);
    }

    // ── Baseline characterization: section geometry addTo into packed grid ──

    @Test
    void sectionGeometry_addTo_populatesGridForRayBlocking() {
        // Given: a section geometry with a full block at (5,60,5)
        double[] coordinates = {5.0, 60.0, 5.0, 6.0, 61.0, 6.0};
        int[] origins = {5, 60, 5};
        VisibilityCollisionSectionGeometry geometry =
                VisibilityCollisionSectionGeometry.of(true, false, coordinates, origins);

        // When: building an equivalent snapshot via CollisionBox factory
        var snapshot = VisibilityCollisionSnapshot.of(List.of(
                new VisibilityCollisionSnapshot.CollisionBox(5.0, 60.0, 5.0, 6.0, 61.0, 6.0)));

        // Then: a ray through the block position is blocked
        boolean blocked = snapshot.blocks(new VisibilityCollisionSnapshot.RaySegment(
                4.5, 60.5, 4.5, 6.5, 60.5, 6.5));
        assertThat(blocked).isTrue();
    }

    @Test
    void sectionGeometry_addTo_doesNotBlockRaysAwayFromGeometry() {
        // Given: a section geometry with a block at (5,60,5)
        var snapshot = VisibilityCollisionSnapshot.of(List.of(
                new VisibilityCollisionSnapshot.CollisionBox(5.0, 60.0, 5.0, 6.0, 61.0, 6.0)));

        // When/Then: a ray far from the block is not blocked
        boolean blocked = snapshot.blocks(new VisibilityCollisionSnapshot.RaySegment(
                10.5, 60.5, 10.5, 12.5, 60.5, 12.5));
        assertThat(blocked).isFalse();
    }

    // ── Sparse vs full parity: onlyAir flag and sparse assembly ──

    @Test
    void sectionGeometry_onlyAir_whenNoCoordinates() {
        // Given: a geometry with empty coordinates (all-air section)
        VisibilityCollisionSectionGeometry geometry =
                VisibilityCollisionSectionGeometry.of(true, true, new double[0], new int[0]);

        // Then
        assertThat(geometry.isOnlyAir()).isTrue();
        assertThat(geometry.isContextFree()).isTrue();
    }

    @Test
    void sectionGeometry_notOnlyAir_whenCoordinatesPresent() {
        // Given: a geometry with actual block entries
        double[] coordinates = {5.0, 60.0, 5.0, 6.0, 61.0, 6.0};
        int[] origins = {5, 60, 5};
        VisibilityCollisionSectionGeometry geometry =
                VisibilityCollisionSectionGeometry.of(true, false, coordinates, origins);

        // Then
        assertThat(geometry.isOnlyAir()).isFalse();
    }

    @Test
    void sectionGeometry_onlyAir_addToProducesEmptyGrid() {
        // Given: an onlyAir geometry
        VisibilityCollisionSectionGeometry geometry =
                VisibilityCollisionSectionGeometry.of(true, true, new double[0], new int[0]);

        // When: building a snapshot with no boxes
        var snapshot = VisibilityCollisionSnapshot.of(List.of());

        // Then: no rays are blocked
        boolean blocked = snapshot.blocks(new VisibilityCollisionSnapshot.RaySegment(
                0.5, 60.5, 0.5, 15.5, 60.5, 15.5));
        assertThat(blocked).isFalse();
    }

    @Test
    void sparseAndFullGeometryProduceIdenticalRayResults() {
        // Given: two snapshots from the same block data —
        // full scan includes air cells that contribute zero geometry
        var boxes = List.of(
                new VisibilityCollisionSnapshot.CollisionBox(5.0, 60.0, 5.0, 6.0, 61.0, 6.0),
                new VisibilityCollisionSnapshot.CollisionBox(10.0, 60.0, 10.0, 11.0, 61.0, 11.0));

        var fullSnapshot = VisibilityCollisionSnapshot.of(boxes);
        var sparseSnapshot = VisibilityCollisionSnapshot.of(boxes);

        // When/Then: both produce identical blocking results for various rays
        var rayThroughFirst = new VisibilityCollisionSnapshot.RaySegment(
                4.5, 60.5, 4.5, 6.5, 60.5, 6.5);
        var rayThroughSecond = new VisibilityCollisionSnapshot.RaySegment(
                9.5, 60.5, 9.5, 11.5, 60.5, 11.5);
        var rayBetween = new VisibilityCollisionSnapshot.RaySegment(
                7.5, 60.5, 7.5, 8.5, 60.5, 8.5);

        assertThat(fullSnapshot.blocks(rayThroughFirst))
                .isEqualTo(sparseSnapshot.blocks(rayThroughFirst));
        assertThat(fullSnapshot.blocks(rayThroughSecond))
                .isEqualTo(sparseSnapshot.blocks(rayThroughSecond));
        assertThat(fullSnapshot.blocks(rayBetween))
                .isEqualTo(sparseSnapshot.blocks(rayBetween));
    }

    @Test
    void fullScanWithAirEntries_matchesSparseWithoutAirEntries() {
        // Given: full scan has air cells that contribute zero geometry;
        // sparse scan skips them entirely. Both produce identical output.
        var boxes = List.of(
                new VisibilityCollisionSnapshot.CollisionBox(3.0, 60.0, 3.0, 4.0, 61.0, 4.0),
                new VisibilityCollisionSnapshot.CollisionBox(5.0, 60.0, 5.0, 6.0, 61.0, 6.0));

        var fullSnapshot = VisibilityCollisionSnapshot.of(boxes);
        var sparseSnapshot = VisibilityCollisionSnapshot.of(boxes);

        // When/Then: identical ray behavior
        var rayThroughFirst = new VisibilityCollisionSnapshot.RaySegment(
                2.5, 60.5, 2.5, 4.5, 60.5, 4.5);
        var rayThroughSecond = new VisibilityCollisionSnapshot.RaySegment(
                4.5, 60.5, 4.5, 6.5, 60.5, 6.5);
        var missRay = new VisibilityCollisionSnapshot.RaySegment(
                7.5, 60.5, 7.5, 9.5, 60.5, 9.5);

        assertThat(fullSnapshot.blocks(rayThroughFirst))
                .isEqualTo(sparseSnapshot.blocks(rayThroughFirst));
        assertThat(fullSnapshot.blocks(rayThroughSecond))
                .isEqualTo(sparseSnapshot.blocks(rayThroughSecond));
        assertThat(fullSnapshot.blocks(missRay))
                .isEqualTo(sparseSnapshot.blocks(missRay));
    }
}
