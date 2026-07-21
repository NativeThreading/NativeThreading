package com.github.uright008.vec.core;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public final class VectorialAgent {

    private static final String ENTITY_CLASS = "net.minecraft.world.entity.Entity";
    private static volatile boolean diagnosticsEnabled;

    private VectorialAgent() {}

    public static void premain(String agentArgs, Instrumentation inst) {
        init(inst, false);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        diagnosticsEnabled = true;
        init(inst, true);
    }

    private static synchronized void init(Instrumentation inst, boolean reportStatus) {
        try {
            inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className,
                                    Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain,
                                    byte[] classfileBuffer) {
                return VectorialTransformer.transform(loader, className, classfileBuffer);
            }

            @Override
            public byte[] transform(Module module, ClassLoader loader, String className,
                                    Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain,
                                    byte[] classfileBuffer) {
                return transform(loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
            }
            }, true);

            for (Class<?> loadedClass : inst.getAllLoadedClasses()) {
                if (ENTITY_CLASS.equals(loadedClass.getName())) {
                    inst.retransformClasses(loadedClass);
                    if (reportStatus) {
                        report(VectorialTransformer.isTransformed()
                                ? "retransformed Entity class"
                                : "Entity retransformation completed without a SoA transform");
                    }
                    return;
                }
            }
            if (reportStatus) report("registered transformer; Entity has not loaded yet");
        } catch (Throwable throwable) {
            if (reportStatus) report("agent initialization failed; SoA remains disabled", throwable);
        }
    }

    static void report(String message) {
        if (!diagnosticsEnabled) return;
        System.err.println("[Vectorial] " + message);
    }

    static void report(String message, Throwable throwable) {
        report(message);
        throwable.printStackTrace(System.err);
    }
}
