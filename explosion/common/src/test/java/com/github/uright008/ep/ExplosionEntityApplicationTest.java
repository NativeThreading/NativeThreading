package com.github.uright008.ep;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExplosionEntityApplicationTest {

    @Test
    void appliesDamageThenResistanceThenPushThenBookkeepingThenHit() {
        List<String> calls = new ArrayList<>();
        ExplosionHelper.EntityDamageResult result = new ExplosionHelper.EntityDamageResult(1, 4.0F, 2.0, 0.0, 0.0);

        ExplosionEntityApplication.apply(result, new ExplosionEntityApplication.Target() {
            @Override
            public void hurt(float damage) {
                calls.add("hurt:" + damage);
            }

            @Override
            public double knockbackResistance() {
                calls.add("resistance");
                return 0.25;
            }

            @Override
            public void push(Vec3 knockback) {
                calls.add("push:" + knockback.x);
            }

            @Override
            public void bookkeep(Vec3 knockback) {
                calls.add("bookkeep:" + knockback.x);
            }

            @Override
            public void onExplosionHit() {
                calls.add("hit");
            }
        });

        assertThat(calls).containsExactly("hurt:4.0", "resistance", "push:1.5", "bookkeep:1.5", "hit");
    }
}
