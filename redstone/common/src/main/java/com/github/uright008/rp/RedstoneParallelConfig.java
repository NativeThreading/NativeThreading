package com.github.uright008.rp;

import com.google.gson.JsonObject;
import com.github.uright008.pc.ParallelConfig;

public final class RedstoneParallelConfig extends ParallelConfig {

    private static final RedstoneParallelConfig INSTANCE = new RedstoneParallelConfig();

    private volatile boolean enabled;
    private volatile boolean wireEnabled;
    private volatile boolean diodeEnabled;
    private volatile int wireThreshold;
    private volatile int maxWorkers;

    private RedstoneParallelConfig() {
        super("redstone");
    }

    public static void init() {
        INSTANCE.initialize();
    }

    @Override
    protected void applyDefaults() {
        enabled = false;
        wireEnabled = false;
        diodeEnabled = false;
        wireThreshold = 64;
        maxWorkers = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
    }

    @Override
    protected void read(JsonObject json) {
        applyDefaults();
        if (json.has("enabled")) enabled = json.get("enabled").getAsBoolean();
        if (json.has("wireEnabled")) wireEnabled = json.get("wireEnabled").getAsBoolean();
        if (json.has("diodeEnabled")) diodeEnabled = json.get("diodeEnabled").getAsBoolean();
        if (json.has("wireThreshold")) wireThreshold = Math.max(2, Math.min(1024, json.get("wireThreshold").getAsInt()));
        if (json.has("maxWorkers")) maxWorkers = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors() * 2, json.get("maxWorkers").getAsInt()));
        logger().info("Redstone: {}", enabled ? "ON" : "OFF");
        logger().info("  wire: {}, diode: {}, threshold={}, maxWorkers={}",
                wireEnabled ? "ON" : "OFF", diodeEnabled ? "ON" : "OFF", wireThreshold, maxWorkers);
    }

    @Override
    protected JsonObject write() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled);
        json.addProperty("wireEnabled", wireEnabled);
        json.addProperty("diodeEnabled", diodeEnabled);
        json.addProperty("wireThreshold", wireThreshold);
        json.addProperty("maxWorkers", maxWorkers);
        return json;
    }

    public static boolean isEnabled() { return INSTANCE.loaded && INSTANCE.enabled; }
    public static void setEnabled(boolean v) { INSTANCE.enabled = v; INSTANCE.save(); }

    public static boolean isWireEnabled() { return INSTANCE.loaded && INSTANCE.enabled && INSTANCE.wireEnabled; }
    public static void setWireEnabled(boolean v) { INSTANCE.wireEnabled = v; INSTANCE.save(); }

    public static boolean isDiodeEnabled() { return INSTANCE.loaded && INSTANCE.enabled && INSTANCE.diodeEnabled; }
    public static void setDiodeEnabled(boolean v) { INSTANCE.diodeEnabled = v; INSTANCE.save(); }
    public static int wireThreshold() { return INSTANCE.wireThreshold; }
    public static void setWireThreshold(int v) { INSTANCE.wireThreshold = Math.max(2, Math.min(1024, v)); INSTANCE.save(); }
    public static int maxWorkers() { return INSTANCE.maxWorkers; }
    public static void setMaxWorkers(int v) { INSTANCE.maxWorkers = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors() * 2, v)); INSTANCE.save(); }

    static TestSettings configureForTesting(boolean enabled, boolean wireEnabled, int wireThreshold, int maxWorkers) {
        TestSettings previous = new TestSettings(INSTANCE.enabled, INSTANCE.wireEnabled,
                INSTANCE.wireThreshold, INSTANCE.maxWorkers);
        INSTANCE.enabled = enabled;
        INSTANCE.wireEnabled = wireEnabled;
        INSTANCE.wireThreshold = Math.max(2, wireThreshold);
        INSTANCE.maxWorkers = Math.max(1, maxWorkers);
        return previous;
    }

    static void restoreForTesting(TestSettings settings) {
        INSTANCE.enabled = settings.enabled();
        INSTANCE.wireEnabled = settings.wireEnabled();
        INSTANCE.wireThreshold = settings.wireThreshold();
        INSTANCE.maxWorkers = settings.maxWorkers();
    }

    record TestSettings(boolean enabled, boolean wireEnabled, int wireThreshold, int maxWorkers) {}

    public static void reloadConfig() { INSTANCE.reload(); }
}
