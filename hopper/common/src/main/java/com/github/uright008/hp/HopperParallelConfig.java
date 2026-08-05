package com.github.uright008.hp;

import com.google.gson.JsonObject;
import com.github.uright008.pc.ParallelConfig;

/**
 * Hopper config, persisted in config/nt.json
 * under the "hopper" section key.
 * Uses parallel-core's {@link ParallelConfig} for JSON persistence.
 */
public final class HopperParallelConfig extends ParallelConfig {

    private static final HopperParallelConfig INSTANCE = new HopperParallelConfig();

    private volatile boolean enabled;

    private HopperParallelConfig() {
        super("hopper");
    }

    // ── lazy init ───────────────────────────────

    public static void init() {
        INSTANCE.initialize();
    }

    // ── read / write ─────────────────────────────

    @Override
    protected void read(JsonObject json) {
        if (json.has("enabled")) enabled = json.get("enabled").getAsBoolean();
        logger().info("Parallel hoppers: {}", enabled ? "ON" : "OFF");
    }

    @Override
    protected JsonObject write() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled);
        return json;
    }

    @Override
    protected void applyDefaults() {
        enabled = true;
    }

    // ── static accessors ─────────────────────────

    public static boolean isEnabled() { return INSTANCE.loaded && INSTANCE.enabled; }
    public static void setEnabled(boolean v) { INSTANCE.enabled = v; INSTANCE.save(); }

    public static void reloadConfig() { INSTANCE.reload(); }
}
