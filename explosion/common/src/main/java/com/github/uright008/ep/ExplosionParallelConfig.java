package com.github.uright008.ep;

import com.google.gson.JsonObject;
import com.github.uright008.pc.ParallelConfig;

/**
 * Explosion config, persisted under the "explosion"
 * section in config/nt.json.
 * Uses parallel-core's {@link ParallelConfig} for JSON persistence.
 */
public final class ExplosionParallelConfig extends ParallelConfig {

    private static final ExplosionParallelConfig INSTANCE = new ExplosionParallelConfig();

    private volatile boolean enabled;

    private ExplosionParallelConfig() {
        super("explosion");
    }

    // ── lazy init ───────────────────────────────

    public static void init() {
        INSTANCE.initialize();
    }

    @Override
    protected void applyDefaults() {
        enabled = true;
    }

    // ── read / write ─────────────────────────────

    @Override
    protected void read(JsonObject json) {
        if (json.has("enabled")) enabled = json.get("enabled").getAsBoolean();
        logger().info("Parallel explosions: {}", enabled ? "ON" : "OFF");
    }

    @Override
    protected JsonObject write() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled);
        return json;
    }

    // ── static accessors ─────────────────────────

    public static boolean isEnabled() { return INSTANCE.loaded && INSTANCE.enabled; }
    public static void setEnabled(boolean v) { INSTANCE.enabled = v; INSTANCE.save(); }

    public static void reloadConfig() { INSTANCE.reload(); }
}
