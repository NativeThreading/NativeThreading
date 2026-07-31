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

    // ── Expression generators (inlined into getter bodies) ──

    /** Position axis read: fields[POSITION_X/Y/Z], fallback this.position.x/y/z */
    private static String posAxisExpr(String axis, int ord) {
        return
            "{ int[] _s = " + S + ".INSTANCE.idToSlotCache;" +
            "  int _sl = (id >= 0 && id < _s.length) ? _s[id] : -1;" +
            "  return _sl >= 0 ? " + S + ".INSTANCE.fields[" + ord + "][_sl]" +
            "                 : this.position." + axis.toLowerCase() + "; }";
    }

    /** Vec3 read from 3 consecutive ordinals, fallback this.{fieldName} */
    private static String vec3Expr(String fieldName, int baseOrd) {
        return
            "{ int[] _s = " + S + ".INSTANCE.idToSlotCache;" +
            "  int _sl = (id >= 0 && id < _s.length) ? _s[id] : -1;" +
            "  if (_sl >= 0) return new net.minecraft.world.phys.Vec3(" +
            "    " + S + ".INSTANCE.fields[" + baseOrd + "][_sl]," +
            "    " + S + ".INSTANCE.fields[" + (baseOrd+1) + "][_sl]," +
            "    " + S + ".INSTANCE.fields[" + (baseOrd+2) + "][_sl]);" +
            "  return this." + fieldName + "; }";
    }

    /** AABB read from 6 consecutive ordinals, fallback this.{fieldName} */
    private static String aabbExpr(String fieldName, int baseOrd) {
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
            "  return this." + fieldName + "; }";
    }

    /** Scalar read (double/float/int) with NaN fallback */
    private static String scalarExpr(String fieldName, int ord, String type) {
        String cast = type.equals("float") ? "(float)" : "";
        return
            "{ int[] _s = " + S + ".INSTANCE.idToSlotCache;" +
            "  int _sl = (id >= 0 && id < _s.length) ? _s[id] : -1;" +
            "  if (_sl >= 0) { double _v = " + S + ".INSTANCE.fields[" + ord + "][_sl];" +
            "    if (!Double.isNaN(_v)) return " + cast + "_v; }" +
            "  return this." + fieldName + "; }";
    }

    /** Boolean read with NaN fallback: return _v != 0.0 */
    private static String boolExpr(String fieldName, int ord) {
        return
            "{ int[] _s = " + S + ".INSTANCE.idToSlotCache;" +
            "  int _sl = (id >= 0 && id < _s.length) ? _s[id] : -1;" +
            "  if (_sl >= 0) { double _v = " + S + ".INSTANCE.fields[" + ord + "][_sl];" +
            "    if (!Double.isNaN(_v)) return _v != 0.0; }" +
            "  return this." + fieldName + "; }";
    }

    // ── Transform logic ──

    private static void transformGetters(CtClass ct) throws Exception {
        // ── Special: position axis getters (getX/Y/Z, getX/Y/Z(double), position()) ──
        for (String ax : new String[]{"X","Y","Z"}) {
            int ord = GeneratedFields.POSITION_X + (ax.charAt(0) - 'X');
            setBodySafe(ct, "get"+ax, posAxisExpr(ax, ord));

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

        // position() → reconstruct Vec3 from SoA
        setBodySafe(ct, "position",
            "{ int[] _s = " + S + ".INSTANCE.idToSlotCache;" +
            "  int _sl = (id >= 0 && id < _s.length) ? _s[id] : -1;" +
            "  if (_sl >= 0) return new net.minecraft.world.phys.Vec3(" +
            "    " + S + ".INSTANCE.fields[" + GeneratedFields.POSITION_X + "][_sl]," +
            "    " + S + ".INSTANCE.fields[" + GeneratedFields.POSITION_Y + "][_sl]," +
            "    " + S + ".INSTANCE.fields[" + GeneratedFields.POSITION_Z + "][_sl]);" +
            "  return this.position; }");

        // getDeltaMovement
        setBodySafe(ct, "getDeltaMovement",
            vec3Expr("deltaMovement", GeneratedFields.DELTA_MOVEMENT_X));

        // getBoundingBox
        setBodySafe(ct, "getBoundingBox",
            aabbExpr("bb", GeneratedFields.BB_MIN_X));

        // getYRot / getXRot / getEyeHeight
        setBodySafe(ct, "getYRot", scalarExpr("yRot", GeneratedFields.Y_ROT, "float"));
        setBodySafe(ct, "getXRot", scalarExpr("xRot", GeneratedFields.X_ROT, "float"));
        setBodySafe(ct, "getEyeHeight", scalarExpr("eyeHeight", GeneratedFields.EYE_HEIGHT, "float"));

        // onGround
        setBodySafe(ct, "onGround", boolExpr("onGround", GeneratedFields.ON_GROUND));

        // ── Auto: new getters from GeneratedAccessors (skip handled + blacklisted) ──
        // TODO: enable after debugging — currently all auto getters cause VerifyError on retransform
        // int count = 0;
        // var manual = java.util.Set.of(
        //     "getDeltaMovement", "getYRot", "getXRot", "getBoundingBox",
        //     "getEyeHeight", "onGround", "getX", "getY", "getZ", "position"
        // );
        // for (GeneratedAccessors.Entry e : GeneratedAccessors.ALL) {
        //     if (e.getterName() == null || e.skipTransform() || manual.contains(e.getterName())) continue;
        //     String body = ...
        // }
        // VectorialAgent.report("transformed " + count + " getters to SoA");
    }

    private static void setBodySafe(CtClass ct, String methodName, String body) {
        try { ct.getDeclaredMethod(methodName).setBody(body); }
        catch (Exception ignored) {}
    }
}
