package com.github.uright008.ep;

import net.minecraft.world.phys.Vec3;

public final class ExplosionEntityApplication {
    private ExplosionEntityApplication() {
    }

    public static void apply(ExplosionHelper.EntityDamageResult result, Target target) {
        if (result.damage() > 0.0F) {
            target.hurt(result.damage());
        }
        Vec3 knockback = result.makeKnockback(target.knockbackResistance());
        target.push(knockback);
        target.bookkeep(knockback);
        target.onExplosionHit();
    }

    public interface Target {
        void hurt(float damage);

        double knockbackResistance();

        void push(Vec3 knockback);

        void bookkeep(Vec3 knockback);

        void onExplosionHit();
    }
}
