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
}
