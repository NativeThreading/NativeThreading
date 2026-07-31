package com.github.uright008.vec.core;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

// AUTO-GENERATED — syncs all captured fields from Entity to SoA
public final class GeneratedSync {
    private GeneratedSync() {}

    public static void syncAll(Entity entity) {
        int id = entity.getId();
        int[] slots = SoAStore.INSTANCE.idToSlotCache;
        int slot = (id >= 0 && id < slots.length) ? slots[id] : -1;
        if (slot < 0) return;
        double[][] f = SoAStore.INSTANCE.fields;

        f[0][slot] = entity.getRequiresPrecisePosition() ? 1.0 : Double.NaN;
        f[1][slot] = entity.getId();
        f[2][slot] = entity.blocksBuilding ? 1.0 : Double.NaN;
        f[4][slot] = entity.xo;
        f[5][slot] = entity.yo;
        f[6][slot] = entity.zo;
        { Vec3 v = entity.position(); if (v != null) { f[7][slot]=v.x; f[8][slot]=v.y; f[9][slot]=v.z; } }
        { Vec3 v = entity.getDeltaMovement(); if (v != null) { f[11][slot]=v.x; f[12][slot]=v.y; f[13][slot]=v.z; } }
        f[14][slot] = entity.getYRot();
        f[15][slot] = entity.getXRot();
        f[16][slot] = entity.yRotO;
        f[17][slot] = entity.xRotO;
        { AABB bb = entity.getBoundingBox(); if (bb != null) { f[18][slot]=bb.minX; f[19][slot]=bb.minY; f[20][slot]=bb.minZ; f[21][slot]=bb.maxX; f[22][slot]=bb.maxY; f[23][slot]=bb.maxZ; } }
        f[24][slot] = entity.onGround() ? 1.0 : Double.NaN;
        f[25][slot] = entity.horizontalCollision ? 1.0 : Double.NaN;
        f[26][slot] = entity.verticalCollision ? 1.0 : Double.NaN;
        f[27][slot] = entity.verticalCollisionBelow ? 1.0 : Double.NaN;
        f[28][slot] = entity.minorHorizontalCollision ? 1.0 : Double.NaN;
        f[29][slot] = entity.hurtMarked ? 1.0 : Double.NaN;
        f[33][slot] = entity.moveDist;
        f[34][slot] = entity.flyDist;
        f[35][slot] = entity.fallDistance;
        f[37][slot] = entity.xOld;
        f[38][slot] = entity.yOld;
        f[39][slot] = entity.zOld;
        f[40][slot] = entity.noPhysics ? 1.0 : Double.NaN;
        f[41][slot] = entity.tickCount;
        f[42][slot] = entity.getRemainingFireTicks();
        f[45][slot] = entity.invulnerableTime;
        f[47][slot] = entity.needsSync ? 1.0 : Double.NaN;
        f[48][slot] = entity.syncPosition ? 1.0 : Double.NaN;
        f[49][slot] = entity.getPortalCooldown();
        f[50][slot] = entity.isInvulnerable() ? 1.0 : Double.NaN;
        f[51][slot] = entity.hasGlowingTag() ? 1.0 : Double.NaN;
        f[52][slot] = entity.getEyeHeight();
        f[53][slot] = entity.isInPowderSnow ? 1.0 : Double.NaN;
        f[54][slot] = entity.wasInPowderSnow ? 1.0 : Double.NaN;
    }
}
