package com.github.uright008.pc;

import com.google.gson.JsonObject;

public final class ParallelCoreConfig extends ParallelConfig {

    private static ParallelCoreConfig INSTANCE = new ParallelCoreConfig();

    private volatile PoolImplementation poolImpl;
    private volatile int poolParallelism;

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
        logger().info("Pool: {} ({} workers)", poolImpl, poolParallelism);
    }

    @Override
    protected JsonObject write() {
        JsonObject json = new JsonObject();
        json.addProperty("poolImplementation", poolImpl.name());
        json.addProperty("poolParallelism", poolParallelism);
        return json;
    }

    public static PoolImplementation poolImplementation() { return INSTANCE.poolImpl; }
    public static void setPoolImplementation(PoolImplementation impl) { INSTANCE.poolImpl = impl; INSTANCE.save(); }
    public static int poolParallelism() { return INSTANCE.poolParallelism; }
    public static void setPoolParallelism(int p) { INSTANCE.poolParallelism = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors() * 2, p)); INSTANCE.save(); }
    public static void reloadConfig() { INSTANCE.reload(); }

    public static void resetForTesting(ConfigStorage storage) {
        INSTANCE = new ParallelCoreConfig(storage);
    }

    private static int getParallelism() {
        return Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
    }
}
