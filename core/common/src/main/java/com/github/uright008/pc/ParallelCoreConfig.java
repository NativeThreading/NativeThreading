package com.github.uright008.pc;

import com.google.gson.JsonObject;

public final class ParallelCoreConfig extends ParallelConfig {

    private static ParallelCoreConfig INSTANCE = new ParallelCoreConfig();

    private volatile PoolImplementation poolImpl;
    private volatile int poolParallelism;
    private volatile boolean simdEnabled;

    private ParallelCoreConfig() {
        super("parallel-core");
    }

    ParallelCoreConfig(ConfigStorage storage) {
        super("parallel-core", storage);
    }

    public static void init() {
        INSTANCE.initialize();
    }

    @Override
    protected void applyDefaults() {
        poolImpl = PoolImplementation.FORK_JOIN;
        poolParallelism = getParallelism();
        simdEnabled = false;
    }

    @Override
    protected void read(JsonObject json) {
        applyDefaults();
        if (json.has("poolImplementation")) {
            try { poolImpl = PoolImplementation.valueOf(json.get("poolImplementation").getAsString()); }
            catch (IllegalArgumentException ignored) {}
        }
        if (json.has("poolParallelism")) {
            poolParallelism = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors() * 2, json.get("poolParallelism").getAsInt()));
        }
        if (json.has("simdEnabled")) {
            simdEnabled = json.get("simdEnabled").getAsBoolean();
        }
        logger().info("Pool: {} ({} workers), SIMD: {}", poolImpl, poolParallelism, simdEnabled);
    }

    @Override
    protected JsonObject write() {
        JsonObject json = new JsonObject();
        json.addProperty("poolImplementation", poolImpl.name());
        json.addProperty("poolParallelism", poolParallelism);
        json.addProperty("simdEnabled", simdEnabled);
        return json;
    }

    public static PoolImplementation poolImplementation() { return INSTANCE.poolImpl; }
    public static void setPoolImplementation(PoolImplementation impl) { INSTANCE.poolImpl = impl; INSTANCE.save(); }
    public static int poolParallelism() { return INSTANCE.poolParallelism; }
    public static void setPoolParallelism(int p) { INSTANCE.poolParallelism = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors() * 2, p)); INSTANCE.save(); }
    public static boolean simdEnabled() { return INSTANCE.simdEnabled; }
    public static void setSimdEnabled(boolean v) { INSTANCE.simdEnabled = v; INSTANCE.save(); }
    public static void reloadConfig() { INSTANCE.reload(); }

    public static void resetForTesting(ConfigStorage storage) {
        INSTANCE = new ParallelCoreConfig(storage);
    }

    private static int getParallelism() {
        int pcores = getPcoreCount();
        if (pcores > 0) return Math.max(2, pcores);
        return Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
    }

    private static int getPcoreCount() {
        try {
            java.io.File cpuDir = new java.io.File("/sys/devices/system/cpu");
            java.io.File[] cpus = cpuDir.listFiles(f -> f.getName().matches("cpu\\d+"));
            if (cpus == null) return 0;
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (java.io.File cpu : cpus) {
                java.io.File siblingsFile = new java.io.File(cpu, "topology/thread_siblings_list");
                if (!siblingsFile.exists()) continue;
                String siblings = new String(java.nio.file.Files.readAllBytes(siblingsFile.toPath())).trim();
                if (siblings.contains(",") || siblings.contains("-")) seen.add(siblings);
            }
            return seen.isEmpty() ? 0 : seen.size();
        } catch (Exception ignored) {
            return 0;
        }
    }
}
