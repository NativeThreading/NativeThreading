package com.github.uright008.vec.core;

import javassist.*;
import javassist.bytecode.*;

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

    // ── Expression generators ──

    private static String posAxisExpr(String axis, int ord) {
        return
            "{ int[] _s = " + S + ".INSTANCE.idToSlotCache;" +
            "  int _sl = (id >= 0 && id < _s.length) ? _s[id] : -1;" +
            "  return _sl >= 0 ? " + S + ".INSTANCE.fields[" + ord + "][_sl]" +
            "                 : this.position." + axis.toLowerCase() + "; }";
    }

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

    private static String scalarExpr(String fieldName, int ord, String type) {
        String cast = switch (type) {
            case "float" -> "(float)";
            case "int"   -> "(int)";
            default      -> "";
        };
        return
            "{ int[] _s = " + S + ".INSTANCE.idToSlotCache;" +
            "  int _sl = (id >= 0 && id < _s.length) ? _s[id] : -1;" +
            "  if (_sl >= 0) { double _v = " + S + ".INSTANCE.fields[" + ord + "][_sl];" +
            "    if (!Double.isNaN(_v)) return " + cast + "_v; }" +
            "  return this." + fieldName + "; }";
    }

    private static String boolExpr(String fieldName, int ord) {
        return
            "{ int[] _s = " + S + ".INSTANCE.idToSlotCache;" +
            "  int _sl = (id >= 0 && id < _s.length) ? _s[id] : -1;" +
            "  if (_sl >= 0) { double _v = " + S + ".INSTANCE.fields[" + ord + "][_sl];" +
            "    if (!Double.isNaN(_v)) return _v != 0.0; }" +
            "  return this." + fieldName + "; }";
    }

    // ── Bytecode-based getter discovery ──

    /** Inspects method bytecode. If it's a simple getter (ALOAD_0 → GETFIELD → RETURN),
     *  returns the field name. Otherwise returns null (including methods with guard logic,
     *  chained getfields, or compound computation). */
    private static String discoverSimpleGetterField(CtMethod method) {
        try {
            MethodInfo minfo = method.getMethodInfo();
            CodeAttribute ca = minfo.getCodeAttribute();
            if (ca == null) return null;
            byte[] code = ca.getCode();
            if (code == null || code.length == 0) return null;

            int i = 0;
            while (i < code.length && (code[i] & 0xFF) == Opcode.NOP) i++;
            if (i >= code.length || (code[i] & 0xFF) != Opcode.ALOAD_0) return null;
            i++;
            while (i < code.length && (code[i] & 0xFF) == Opcode.NOP) i++;
            if (i + 2 >= code.length || (code[i] & 0xFF) != Opcode.GETFIELD) return null;
            int cpIndex = ((code[i+1] & 0xFF) << 8) | (code[i+2] & 0xFF);
            i += 3;
            while (i < code.length && (code[i] & 0xFF) == Opcode.NOP) i++;
            if (i >= code.length) return null;
            int retOp = code[i] & 0xFF;
            if (retOp < Opcode.IRETURN || retOp > Opcode.RETURN) return null;
            i++;
            while (i < code.length) {
                if ((code[i] & 0xFF) != Opcode.NOP) return null;
                i++;
            }

            if (!fieldBelongsToEntity(minfo, cpIndex)) return null;
            return minfo.getConstPool().getFieldrefName(cpIndex);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean fieldBelongsToEntity(MethodInfo minfo, int cpIndex) {
        String className = minfo.getConstPool().getFieldrefClassName(cpIndex);
        return "net/minecraft/world/entity/Entity".equals(className)
            || "net.minecraft.world.entity.Entity".equals(className);
    }

    // ── Transform logic ──

    private static void transformGetters(CtClass ct) throws Exception {
        // Position axis getters — chained GETFIELD (position → component), handled specially
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

        // Bytecode-based auto-discovery: find ALL simple getters on Entity
        int count = 0;
        var skipNames = java.util.Set.of("getX", "getY", "getZ", "position");
        for (CtMethod m : ct.getDeclaredMethods()) {
            if (m.getParameterTypes().length > 0) continue;
            if (skipNames.contains(m.getName())) continue;

            String fieldName = discoverSimpleGetterField(m);
            if (fieldName == null) continue;

            GeneratedFields.Spec spec = GeneratedFields.forName(fieldName);
            if (spec == null) continue;

            String body = switch (spec.type()) {
                case "double" -> scalarExpr(fieldName, spec.ordinal(), "double");
                case "float"  -> scalarExpr(fieldName, spec.ordinal(), "float");
                case "int"    -> scalarExpr(fieldName, spec.ordinal(), "int");
                case "boolean"-> boolExpr(fieldName, spec.ordinal());
                case "Vec3"   -> vec3Expr(fieldName, spec.ordinal());
                case "AABB"   -> aabbExpr(fieldName, spec.ordinal());
                default       -> null;
            };

            if (body != null) {
                try { m.setBody(body); count++; }
                catch (Exception ignored) {}
            }
        }
        VectorialAgent.report("transformed " + count + " getters to SoA");
    }

    private static void setBodySafe(CtClass ct, String methodName, String body) {
        try { ct.getDeclaredMethod(methodName).setBody(body); }
        catch (Exception ignored) {}
    }
}
