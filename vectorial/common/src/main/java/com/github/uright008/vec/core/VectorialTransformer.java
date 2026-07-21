package com.github.uright008.vec.core;

import javassist.*;

public final class VectorialTransformer {
    private static final String E = "net.minecraft.world.entity.Entity";
    private static final String S = "com.github.uright008.vec.core.SoAStore";
    private static volatile boolean transformed;

    private VectorialTransformer() {}

    public static boolean isTransformed() {
        return transformed;
    }

    public static byte[] transform(ClassLoader loader, String className, byte[] originalBytes) {
        if (transformed) return null;
        if (!E.equals(className.replace('/', '.'))) return null;
        try {
            ClassPool pool = new ClassPool(true);
            pool.insertClassPath(new LoaderClassPath(loader));
            pool.insertClassPath(new ByteArrayClassPath(E, originalBytes));
            pool.get("com.github.uright008.vec.core.SoAStore");
            CtClass ct = pool.get(E);
            transformGetters(ct);
            byte[] out = ct.toBytecode();
            ct.detach();
            transformed = true;
            VectorialAgent.report("transformed Entity fields to SoA");
            return out;
        } catch (Throwable throwable) {
            VectorialAgent.report("Entity transformation failed; SoA remains disabled", throwable);
            return null;
        }
    }

    // Single-element read helper — inlined into getter bodies.
    // Accesses SoAStore.idToSlot and SoAStore.fields directly (zero method calls).
    // Falls back to original field when entity is not yet registered.
    private static String readExpr(String axis, int ord) {
        return
            "{ int[] _s = " + S + ".INSTANCE.idToSlotCache;" +
            "  int _sl = (id >= 0 && id < _s.length) ? _s[id] : -1;" +
            "  return _sl >= 0 ? " + S + ".INSTANCE.fields[" + ord + "][_sl]" +
            "                 : this.position." + axis.toLowerCase() + "; }";
    }

    // Vec3 read: reconstruct Vec3 from 3 consecutive ordinals
    private static String vec3Expr(String axis, int baseOrd) {
        String lo = axis.toLowerCase();
        return
            "{ int[] _s = " + S + ".INSTANCE.idToSlotCache;" +
            "  int _sl = (id >= 0 && id < _s.length) ? _s[id] : -1;" +
            "  if (_sl >= 0) return new net.minecraft.world.phys.Vec3(" +
            "    " + S + ".INSTANCE.fields[" + baseOrd + "][_sl]," +
            "    " + S + ".INSTANCE.fields[" + (baseOrd+1) + "][_sl]," +
            "    " + S + ".INSTANCE.fields[" + (baseOrd+2) + "][_sl]);" +
            "  return this." + lo + "; }";
    }

    // AABB read: reconstruct AABB from 6 consecutive ordinals
    private static String aabbExpr(int baseOrd) {
        return
            "{ int[] _s = " + S + ".INSTANCE.idToSlotCache;" +
            "  int _sl = (id >= 0 && id < _s.length) ? _s[id] : -1;" +
            "  if (_sl >= 0) return new net.minecraft.world.phys.AABB(" +
            "    " + S + ".INSTANCE.fields[" + baseOrd + "][_sl]," +
            "    " + S + ".INSTANCE.fields[" + (baseOrd+1) + "][_sl]," +
            "    " + S + ".INSTANCE.fields[" + (baseOrd+2) + "][_sl]," +
            "    " + S + ".INSTANCE.fields[" + (baseOrd+3) + "][_sl]," +
            "    " + S + ".INSTANCE.fields[" + (baseOrd+4) + "][_sl]," +
            "    " + S + ".INSTANCE.fields[" + (baseOrd+5) + "][_sl]);" +
            "  return this.bb; }";
    }

    // Scalar read with NaN fallback for float/int fields
    private static String scalarExpr(String fieldName, int ord, String type) {
        String fallback = "this." + fieldName;
        String cast = type.equals("float") ? "(float)" : "";
        return
            "{ int[] _s = " + S + ".INSTANCE.idToSlotCache;" +
            "  int _sl = (id >= 0 && id < _s.length) ? _s[id] : -1;" +
            "  if (_sl >= 0) { double _v = " + S + ".INSTANCE.fields[" + ord + "][_sl];" +
            "    if (!Double.isNaN(_v)) return " + cast + "_v; }" +
            "  return " + fallback + "; }";
    }

    private static void transformGetters(CtClass ct) throws Exception {
        // getX/Y/Z → inline idToSlot + fields[POSITION_X/Y/Z][slot]
        for (String ax : new String[]{"X","Y","Z"}) {
            int ord = GeneratedFields.POSITION_X + (ax.charAt(0) - 'X');
            setBodySafe(ct, "get"+ax, readExpr(ax, ord));

            // getX/Y/Z(double progress) — used in ray tracing, projectile collisions
            try {
                CtMethod m = ct.getDeclaredMethod("get"+ax,
                        new CtClass[]{ct.getClassPool().get("double")});
                m.setBody(
                    "{ int[] _s = " + S + ".INSTANCE.idToSlotCache;" +
                    "  int _sl = (id >= 0 && id < _s.length) ? _s[id] : -1;" +
                    "  double _px = _sl >= 0 ? " + S + ".INSTANCE.fields[" + ord + "][_sl]" +
                    "                     : this.position." + ax.toLowerCase() + ";" +
                    "  return _px + this.getBbWidth() * $1; }");
            } catch (NotFoundException ignored) {}
        }

        // position() → reconstruct Vec3 from SoA with field fallback
        setBodySafe(ct, "position",
            "{ int[] _s = " + S + ".INSTANCE.idToSlotCache;" +
            "  int _sl = (id >= 0 && id < _s.length) ? _s[id] : -1;" +
            "  if (_sl >= 0) return new net.minecraft.world.phys.Vec3(" +
            "    " + S + ".INSTANCE.fields[" + GeneratedFields.POSITION_X + "][_sl]," +
            "    " + S + ".INSTANCE.fields[" + GeneratedFields.POSITION_Y + "][_sl]," +
            "    " + S + ".INSTANCE.fields[" + GeneratedFields.POSITION_Z + "][_sl]);" +
            "  return this.position; }");

        // getDeltaMovement → inline Vec3 from fields[DELTA_MOVEMENT_X..Z]
        setBodySafe(ct, "getDeltaMovement",
            vec3Expr("deltaMovement", GeneratedFields.DELTA_MOVEMENT_X));

        // getBoundingBox → inline AABB from fields[BB_MIN_X..BB_MAX_Z]
        setBodySafe(ct, "getBoundingBox",
            aabbExpr(GeneratedFields.BB_MIN_X));

        // getYRot / getXRot / getEyeHeight
        setBodySafe(ct, "getYRot", scalarExpr("yRot", GeneratedFields.Y_ROT, "float"));
        setBodySafe(ct, "getXRot", scalarExpr("xRot", GeneratedFields.X_ROT, "float"));
        setBodySafe(ct, "getEyeHeight", scalarExpr("eyeHeight", GeneratedFields.EYE_HEIGHT, "float"));

        // onGround() — most-called boolean on Entity
        setBodySafe(ct, "onGround",
            "{ int[] _s = " + S + ".INSTANCE.idToSlotCache;" +
            "  int _sl = (id >= 0 && id < _s.length) ? _s[id] : -1;" +
            "  if (_sl >= 0) { double _v = " + S + ".INSTANCE.fields[" + GeneratedFields.ON_GROUND + "][_sl];" +
            "    if (!Double.isNaN(_v)) return _v != 0.0; }" +
            "  return this.onGround; }");
    }

    private static void setBodySafe(CtClass ct, String methodName, String body) {
        try { ct.getDeclaredMethod(methodName).setBody(body); }
        catch (Exception ignored) {}
    }
}
