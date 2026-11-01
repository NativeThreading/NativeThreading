package com.github.uright008.nt.explosion;

import com.google.gson.JsonObject;
import com.github.uright008.nt.core.NtConfig;

/**
 * Explosion config, persisted under the "explosion"
 * section in config/nt.json.
 * Uses native-threading-core's {@link NtConfig} for JSON persistence.
 */
public final class ExplosionConfig extends NtConfig {

    private static final ExplosionConfig INSTANCE = new ExplosionConfig();

    private volatile boolean enabled;

    private ExplosionConfig() {
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
        logger().info("Explosion pipeline: {}", enabled ? "ON" : "OFF");
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
