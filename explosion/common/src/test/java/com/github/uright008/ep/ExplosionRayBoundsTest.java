package com.github.uright008.ep;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExplosionRayBoundsTest {

    @Test
    void containsFarthestUnobstructedVanillaRayStep() {
        float radius = 4.0F;
        ExplosionRayBounds bounds = ExplosionRayBounds.forExplosion(new Vec3(0.0, 0.0, 0.0), radius);
        int farthestRayBlock = 6;

        assertThat(bounds.maxX())
                .as("the captured grid must include the final unobstructed positive ray block")
                .isGreaterThanOrEqualTo(farthestRayBlock);
        assertThat(bounds.minX())
                .as("the captured grid must include the final unobstructed negative ray block")
                .isLessThanOrEqualTo(-farthestRayBlock);
    }

    @Test
    void retainsFarthestReachForFractionalCenterCoordinates() {
        ExplosionRayBounds bounds = ExplosionRayBounds.forExplosion(new Vec3(0.9, -0.9, 0.1), 4.0F);

        assertThat(bounds.maxX()).isGreaterThanOrEqualTo(7);
        assertThat(bounds.minY()).isLessThanOrEqualTo(-8);
    }
}
