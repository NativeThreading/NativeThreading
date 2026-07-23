package com.github.uright008.ep;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExplosionKnockbackTest {

    @Test
    void zeroLengthDirectionProducesVanillaZeroKnockback() {
        Vec3 knockback = ExplosionHelper.knockback(0.0, 0.0, 0.0, 1.0);

        assertThat(knockback).isEqualTo(Vec3.ZERO);
        assertThat(knockback.x).isFinite();
        assertThat(knockback.y).isFinite();
        assertThat(knockback.z).isFinite();
    }
}
