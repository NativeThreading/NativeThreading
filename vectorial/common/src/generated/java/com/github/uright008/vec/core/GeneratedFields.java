package com.github.uright008.vec.core;

// AUTO-GENERATED from Entity.class via javap — do not edit
public final class GeneratedFields {
    public record Spec(String name, String type, String access, int ordinal) {
        public boolean isDouble() { return type.equals("double") || type.equals("Vec3"); }
        public boolean isFloat() { return type.equals("float"); }
        public boolean isInt() { return type.equals("int"); }
        public boolean isBoolean() { return type.equals("boolean"); }
        public boolean isVec3() { return type.equals("Vec3"); }
        public boolean isAABB() { return type.equals("AABB"); }
    }
    public static Spec forName(String fieldName) {
        for (Spec s : ALL) { if (s.name().equals(fieldName)) return s; }
        return null;
    }
    public static final Spec[] ALL = {
        new Spec("requiresPrecisePosition", "boolean", "private", 0),
        new Spec("id", "int", "private", 1),
        new Spec("blocksBuilding", "boolean", "public", 2),
        new Spec("boardingCooldown", "int", "protected", 3),
        new Spec("xo", "double", "public", 4),
        new Spec("yo", "double", "public", 5),
        new Spec("zo", "double", "public", 6),
        new Spec("position", "Vec3", "private", 7),
        new Spec("blockPosition", "BlockPos", "private", 10),
        new Spec("deltaMovement", "Vec3", "private", 11),
        new Spec("yRot", "float", "private", 14),
        new Spec("xRot", "float", "private", 15),
        new Spec("yRotO", "float", "public", 16),
        new Spec("xRotO", "float", "public", 17),
        new Spec("bb", "AABB", "private", 18),
        new Spec("onGround", "boolean", "private", 24),
        new Spec("horizontalCollision", "boolean", "public", 25),
        new Spec("verticalCollision", "boolean", "public", 26),
        new Spec("verticalCollisionBelow", "boolean", "public", 27),
        new Spec("minorHorizontalCollision", "boolean", "public", 28),
        new Spec("hurtMarked", "boolean", "public", 29),
        new Spec("stuckSpeedMultiplier", "Vec3", "protected", 30),
        new Spec("moveDist", "float", "public", 33),
        new Spec("flyDist", "float", "public", 34),
        new Spec("fallDistance", "double", "public", 35),
        new Spec("nextStep", "float", "private", 36),
        new Spec("xOld", "double", "public", 37),
        new Spec("yOld", "double", "public", 38),
        new Spec("zOld", "double", "public", 39),
        new Spec("noPhysics", "boolean", "public", 40),
        new Spec("tickCount", "int", "public", 41),
        new Spec("remainingFireTicks", "int", "private", 42),
        new Spec("wasTouchingWater", "boolean", "protected", 43),
        new Spec("wasEyeInWater", "boolean", "protected", 44),
        new Spec("invulnerableTime", "int", "public", 45),
        new Spec("firstTick", "boolean", "protected", 46),
        new Spec("needsSync", "boolean", "public", 47),
        new Spec("syncPosition", "boolean", "public", 48),
        new Spec("portalCooldown", "int", "private", 49),
        new Spec("invulnerable", "boolean", "private", 50),
        new Spec("hasGlowingTag", "boolean", "private", 51),
        new Spec("eyeHeight", "float", "private", 52),
        new Spec("isInPowderSnow", "boolean", "public", 53),
        new Spec("wasInPowderSnow", "boolean", "public", 54),
        new Spec("onGroundNoBlocks", "boolean", "private", 55),
        new Spec("crystalSoundIntensity", "float", "private", 56),
        new Spec("lastCrystalSoundPlayTick", "int", "private", 57),
        new Spec("hasVisualFire", "boolean", "private", 58),
        new Spec("lastKnownSpeed", "Vec3", "private", 59),
        new Spec("lastKnownPosition", "Vec3", "private", 62) 
    };

    // ── Field ordinals (array index) ──
    public static final int REQUIRES_PRECISE_POSITION = 0;
    public static final int ID = 1;
    public static final int BLOCKS_BUILDING = 2;
    public static final int BOARDING_COOLDOWN = 3;
    public static final int XO = 4;
    public static final int YO = 5;
    public static final int ZO = 6;
    public static final int POSITION_X = 7;
    public static final int POSITION_Y = 8;
    public static final int POSITION_Z = 9;
    public static final int DELTA_MOVEMENT_X = 11;
    public static final int DELTA_MOVEMENT_Y = 12;
    public static final int DELTA_MOVEMENT_Z = 13;
    public static final int Y_ROT = 14;
    public static final int X_ROT = 15;
    public static final int Y_ROT_O = 16;
    public static final int X_ROT_O = 17;
    public static final int BB_MIN_X = 18;
    public static final int BB_MIN_Y = 19;
    public static final int BB_MIN_Z = 20;
    public static final int BB_MAX_X = 21;
    public static final int BB_MAX_Y = 22;
    public static final int BB_MAX_Z = 23;
    public static final int ON_GROUND = 24;
    public static final int HORIZONTAL_COLLISION = 25;
    public static final int VERTICAL_COLLISION = 26;
    public static final int VERTICAL_COLLISION_BELOW = 27;
    public static final int MINOR_HORIZONTAL_COLLISION = 28;
    public static final int HURT_MARKED = 29;
    public static final int STUCK_SPEED_MULTIPLIER_X = 30;
    public static final int STUCK_SPEED_MULTIPLIER_Y = 31;
    public static final int STUCK_SPEED_MULTIPLIER_Z = 32;
    public static final int MOVE_DIST = 33;
    public static final int FLY_DIST = 34;
    public static final int FALL_DISTANCE = 35;
    public static final int NEXT_STEP = 36;
    public static final int X_OLD = 37;
    public static final int Y_OLD = 38;
    public static final int Z_OLD = 39;
    public static final int NO_PHYSICS = 40;
    public static final int TICK_COUNT = 41;
    public static final int REMAINING_FIRE_TICKS = 42;
    public static final int WAS_TOUCHING_WATER = 43;
    public static final int WAS_EYE_IN_WATER = 44;
    public static final int INVULNERABLE_TIME = 45;
    public static final int FIRST_TICK = 46;
    public static final int NEEDS_SYNC = 47;
    public static final int SYNC_POSITION = 48;
    public static final int PORTAL_COOLDOWN = 49;
    public static final int INVULNERABLE = 50;
    public static final int HAS_GLOWING_TAG = 51;
    public static final int EYE_HEIGHT = 52;
    public static final int IS_IN_POWDER_SNOW = 53;
    public static final int WAS_IN_POWDER_SNOW = 54;
    public static final int ON_GROUND_NO_BLOCKS = 55;
    public static final int CRYSTAL_SOUND_INTENSITY = 56;
    public static final int LAST_CRYSTAL_SOUND_PLAY_TICK = 57;
    public static final int HAS_VISUAL_FIRE = 58;
    public static final int LAST_KNOWN_SPEED_X = 59;
    public static final int LAST_KNOWN_SPEED_Y = 60;
    public static final int LAST_KNOWN_SPEED_Z = 61;
    public static final int LAST_KNOWN_POSITION_X = 62;
    public static final int LAST_KNOWN_POSITION_Y = 63;
    public static final int LAST_KNOWN_POSITION_Z = 64;
    public static final int COUNT = 65;
}
