package com.github.uright008.ep;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Pure entity-damage computation for the worker phase (and the main-thread
 *  serial retrace): distance, knockback vector and vanilla damage amount from
 *  a captured snapshot, with the exact vanilla product order. */
public final class ExplosionEntityDamageComputer {

    public record EntityDamageSnapshot(
            int entityId,
            double feetX,
            double feetY,
            double feetZ,
            double eyeY,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            boolean shouldDamage,
            float knockbackMultiplier,
            float exposure,
            boolean exposurePreset,
            double knockbackResistance) {
    }

    public record EntityDamageResult(
            int entityId,
            float damage,
            double kbX,
            double kbY,
            double kbZ) {
        public Vec3 makeKnockback() { return new Vec3(kbX, kbY, kbZ); }
    }

    private ExplosionEntityDamageComputer() {}

    public static EntityDamageResult computeEntityDamage(
            EntityDamageSnapshot snapshot,
            double centerX,
            double centerY,
            double centerZ,
            float doubleRadius,
            WorldReadView<BlockState> worldView) {
        float exposure;
        if (snapshot.exposurePreset) {
            exposure = snapshot.exposure;
        } else if (snapshot.shouldDamage || snapshot.knockbackMultiplier != 0.0F) {
            exposure = ExplosionExposureComputer.getSeenPercentFromFlatView(
                    snapshot, centerX, centerY, centerZ, worldView);
        } else {
            exposure = 0.0F;
        }
        return computeEntityDamage(snapshot, centerX, centerY, centerZ, doubleRadius, exposure);
    }

    private static EntityDamageResult computeEntityDamage(
            EntityDamageSnapshot snapshot,
            double centerX,
            double centerY,
            double centerZ,
            float doubleRadius,
            float exposure) {
        double dx = snapshot.feetX - centerX;
        double dy = snapshot.feetY - centerY;
        double dz = snapshot.feetZ - centerZ;
        double distanceRatio = Math.sqrt(dx * dx + dy * dy + dz * dz) / doubleRadius;
        // Vanilla: knockbackPower = (1.0 - dist) * exposure * knockbackMultiplier
        // * (1.0 - knockbackResistance) in one product. Resistance is captured
        // into the snapshot on the main thread so the worker computes the exact
        // same product order.
        double power = (1.0 - distanceRatio) * exposure * snapshot.knockbackMultiplier
                * (1.0 - snapshot.knockbackResistance);
        double knockbackX = snapshot.feetX - centerX;
        double knockbackY = snapshot.eyeY - centerY;
        double knockbackZ = snapshot.feetZ - centerZ;
        double knockbackLength = Math.sqrt(knockbackX * knockbackX
                + knockbackY * knockbackY + knockbackZ * knockbackZ);
        if (knockbackLength >= 1.0E-5F) {
            // Vanilla: direction.normalize().scale(power) = (component/len)*power.
            // Divide first, then multiply — matches vanilla's double rounding.
            knockbackX = (knockbackX / knockbackLength) * power;
            knockbackY = (knockbackY / knockbackLength) * power;
            knockbackZ = (knockbackZ / knockbackLength) * power;
        } else {
            knockbackX = 0.0;
            knockbackY = 0.0;
            knockbackZ = 0.0;
        }
        float damage = snapshot.shouldDamage
                ? vanillaDamage(doubleRadius, distanceRatio, exposure)
                : 0.0F;
        return new EntityDamageResult(snapshot.entityId,
                damage, knockbackX, knockbackY, knockbackZ);
    }

    private static float vanillaDamage(float doubleRadius, double distanceRatio, float exposure) {
        double power = (1.0 - distanceRatio) * exposure;
        return (float) ((power * power + power) / 2.0 * 7.0 * doubleRadius + 1.0);
    }

    public static Vec3 knockback(double x, double y, double z, double power) {
        double length = Math.sqrt(x * x + y * y + z * z);
        return length < 1.0E-5F ? Vec3.ZERO : new Vec3(x / length * power, y / length * power, z / length * power);
    }
}
