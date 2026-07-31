package com.github.uright008.vec.mixin;

import com.github.uright008.vec.core.GeneratedFields;
import com.github.uright008.vec.core.GeneratedSync;
import com.github.uright008.vec.core.SoAStore;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMixin {

    // Reusable ordinal/value arrays for hot-path batched setDoubles
    private static final int[] POS_ORDS = {GeneratedFields.POSITION_X, GeneratedFields.POSITION_Y, GeneratedFields.POSITION_Z};
    private static final int[] DELTA_ORDS = {GeneratedFields.DELTA_MOVEMENT_X, GeneratedFields.DELTA_MOVEMENT_Y, GeneratedFields.DELTA_MOVEMENT_Z};
    private static final int[] BB_ORDS = {GeneratedFields.BB_MIN_X, GeneratedFields.BB_MIN_Y, GeneratedFields.BB_MIN_Z,
                                          GeneratedFields.BB_MAX_X, GeneratedFields.BB_MAX_Y, GeneratedFields.BB_MAX_Z};

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onConstruct(CallbackInfo ci) {
        SoAStore.register((Entity)(Object)this);
    }

    @Inject(method = "remove(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", at = @At("HEAD"))
    private void onRemove(CallbackInfo ci) {
        SoAStore.unregister((Entity)(Object)this);
    }

    // Periodic full sync at end of each tick — covers all fields without individual setter injects
    @Inject(method = "tick", at = @At("TAIL"))
    private void onTickTail(CallbackInfo ci) {
        GeneratedSync.syncAll((Entity)(Object)this);
    }

    // ── Hot-path setter syncs (called many times per tick) ──

    @Inject(method = "setPosRaw(DDD)V", at = @At("RETURN"))
    private void onSetPosRaw(double x, double y, double z, CallbackInfo ci) {
        SoAStore.setDoubles(((Entity)(Object)this).getId(), POS_ORDS, new double[]{x, y, z});
    }

    @Inject(method = "setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V", at = @At("RETURN"))
    private void onSetDeltaMovement(net.minecraft.world.phys.Vec3 dm, CallbackInfo ci) {
        if (!dm.isFinite()) return;
        SoAStore.setDoubles(((Entity)(Object)this).getId(), DELTA_ORDS, new double[]{dm.x, dm.y, dm.z});
    }

    @Inject(method = "setDeltaMovement(DDD)V", at = @At("RETURN"))
    private void onSetDeltaMovementDDD(double xd, double yd, double zd, CallbackInfo ci) {
        SoAStore.setDoubles(((Entity)(Object)this).getId(), DELTA_ORDS, new double[]{xd, yd, zd});
    }

    @Inject(method = "setBoundingBox(Lnet/minecraft/world/phys/AABB;)V", at = @At("RETURN"))
    private void onSetBoundingBox(net.minecraft.world.phys.AABB bb, CallbackInfo ci) {
        SoAStore.setDoubles(((Entity)(Object)this).getId(), BB_ORDS, new double[]{bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ});
    }

    @Inject(method = "setYRot(F)V", at = @At("RETURN"))
    private void onSetYRot(float yRot, CallbackInfo ci) {
        SoAStore.setDouble(((Entity)(Object)this).getId(), GeneratedFields.Y_ROT, yRot);
    }

    @Inject(method = "setXRot(F)V", at = @At("RETURN"))
    private void onSetXRot(float xRot, CallbackInfo ci) {
        SoAStore.setDouble(((Entity)(Object)this).getId(), GeneratedFields.X_ROT, xRot);
    }

    @Inject(method = "setOnGround(Z)V", at = @At("RETURN"))
    private void onSetOnGround(boolean onGround, CallbackInfo ci) {
        SoAStore.setDouble(((Entity)(Object)this).getId(), GeneratedFields.ON_GROUND, onGround ? 1.0 : Double.NaN);
    }

    // ── Auto-discovered setter syncs from GeneratedAccessors ──
    @Inject(method = "setRemainingFireTicks(I)V", at = @At("RETURN"))
    private void onSetRemainingFireTicks(int ticks, CallbackInfo ci) {
        SoAStore.setDouble(((Entity)(Object)this).getId(), GeneratedFields.REMAINING_FIRE_TICKS, ticks);
    }

    @Inject(method = "setPortalCooldown(I)V", at = @At("RETURN"))
    private void onSetPortalCooldown(int cooldown, CallbackInfo ci) {
        SoAStore.setDouble(((Entity)(Object)this).getId(), GeneratedFields.PORTAL_COOLDOWN, cooldown);
    }

    @Inject(method = "setInvulnerable(Z)V", at = @At("RETURN"))
    private void onSetInvulnerable(boolean invulnerable, CallbackInfo ci) {
        SoAStore.setDouble(((Entity)(Object)this).getId(), GeneratedFields.INVULNERABLE, invulnerable ? 1.0 : Double.NaN);
    }

    @Inject(method = "setIsInPowderSnow(Z)V", at = @At("RETURN"))
    private void onSetIsInPowderSnow(boolean inSnow, CallbackInfo ci) {
        SoAStore.setDouble(((Entity)(Object)this).getId(), GeneratedFields.IS_IN_POWDER_SNOW, inSnow ? 1.0 : Double.NaN);
    }
}
