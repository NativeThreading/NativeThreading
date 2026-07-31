package com.github.uright008.vec.core;

// AUTO-GENERATED — maps Entity field names to getter/setter methods
public final class GeneratedAccessors {
    public record Entry(String fieldName, String type, String getterName, String setterName, int baseOrdinal) {
        public int ordCount() { return switch (type) { case "Vec3" -> 3; case "AABB" -> 6; default -> 1; }; }
        public boolean skipTransform() {
            return java.util.Set.of().contains(fieldName);
        }
    }
    public static final Entry[] ALL = {
        new Entry("requiresPrecisePosition", "boolean", "getRequiresPrecisePosition", "setRequiresPrecisePosition", 0),
        new Entry("id", "int", "getId", "setId", 1),
        new Entry("blocksBuilding", "boolean", null, null, 2),
        new Entry("boardingCooldown", "int", null, null, 3),
        new Entry("xo", "double", null, null, 4),
        new Entry("yo", "double", null, null, 5),
        new Entry("zo", "double", null, null, 6),
        new Entry("position", "Vec3", "position", null, 7),
        new Entry("blockPosition", "BlockPos", null, null, 10),
        new Entry("deltaMovement", "Vec3", "getDeltaMovement", "setDeltaMovement", 11),
        new Entry("yRot", "float", "getYRot", "setYRot", 14),
        new Entry("xRot", "float", "getXRot", "setXRot", 15),
        new Entry("yRotO", "float", null, null, 16),
        new Entry("xRotO", "float", null, null, 17),
        new Entry("bb", "AABB", "getBoundingBox", "setBoundingBox", 18),
        new Entry("onGround", "boolean", "onGround", "setOnGround", 24),
        new Entry("horizontalCollision", "boolean", null, null, 25),
        new Entry("verticalCollision", "boolean", null, null, 26),
        new Entry("verticalCollisionBelow", "boolean", null, null, 27),
        new Entry("minorHorizontalCollision", "boolean", null, null, 28),
        new Entry("hurtMarked", "boolean", null, null, 29),
        new Entry("stuckSpeedMultiplier", "Vec3", null, null, 30),
        new Entry("moveDist", "float", null, null, 33),
        new Entry("flyDist", "float", null, null, 34),
        new Entry("fallDistance", "double", null, null, 35),
        new Entry("nextStep", "float", null, null, 36),
        new Entry("xOld", "double", null, null, 37),
        new Entry("yOld", "double", null, null, 38),
        new Entry("zOld", "double", null, null, 39),
        new Entry("noPhysics", "boolean", null, null, 40),
        new Entry("tickCount", "int", null, null, 41),
        new Entry("remainingFireTicks", "int", "getRemainingFireTicks", "setRemainingFireTicks", 42),
        new Entry("wasTouchingWater", "boolean", null, null, 43),
        new Entry("wasEyeInWater", "boolean", null, null, 44),
        new Entry("invulnerableTime", "int", null, null, 45),
        new Entry("firstTick", "boolean", null, null, 46),
        new Entry("needsSync", "boolean", null, null, 47),
        new Entry("syncPosition", "boolean", null, null, 48),
        new Entry("portalCooldown", "int", "getPortalCooldown", "setPortalCooldown", 49),
        new Entry("invulnerable", "boolean", "isInvulnerable", "setInvulnerable", 50),
        new Entry("hasGlowingTag", "boolean", "hasGlowingTag", null, 51),
        new Entry("eyeHeight", "float", "getEyeHeight", null, 52),
        new Entry("isInPowderSnow", "boolean", null, "setIsInPowderSnow", 53),
        new Entry("wasInPowderSnow", "boolean", null, null, 54),
        new Entry("onGroundNoBlocks", "boolean", null, null, 55),
        new Entry("crystalSoundIntensity", "float", null, null, 56),
        new Entry("lastCrystalSoundPlayTick", "int", null, null, 57),
        new Entry("hasVisualFire", "boolean", null, null, 58),
        new Entry("lastKnownSpeed", "Vec3", null, null, 59),
        new Entry("lastKnownPosition", "Vec3", null, null, 62) 
    };
}
