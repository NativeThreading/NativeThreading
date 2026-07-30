package com.github.uright008.ep;

import com.google.gson.JsonObject;
import com.github.uright008.pc.ParallelConfig;

/**
 * Explosion config, persisted under the "explosion"
 * section in config/mc-parallel.json.
 * Uses parallel-core's {@link ParallelConfig} for JSON persistence.
 */
public final class ExplosionParallelConfig extends ParallelConfig {

    private static final ExplosionParallelConfig INSTANCE = new ExplosionParallelConfig();

    private volatile boolean enabled;
    private volatile float samplingFactor;
    private volatile boolean rayLookup;
    private volatile int adaptiveRays;
    private volatile boolean preciseRays;

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
        samplingFactor = 2.0f;
        rayLookup = false;
        adaptiveRays = 0;
        preciseRays = true;
    }

    // ── read / write ─────────────────────────────

    @Override
    protected void read(JsonObject json) {
        if (json.has("enabled")) enabled = json.get("enabled").getAsBoolean();
        if (json.has("samplingFactor")) samplingFactor = Math.max(0.1f, Math.min(10.0f, json.get("samplingFactor").getAsFloat()));
        else samplingFactor = 2.0f;
        if (json.has("rayLookup")) rayLookup = json.get("rayLookup").getAsBoolean();
        else rayLookup = false;
        if (json.has("adaptiveRays")) adaptiveRays = json.get("adaptiveRays").getAsInt();
        else adaptiveRays = 0;
        if (json.has("preciseRays")) preciseRays = json.get("preciseRays").getAsBoolean();
        else preciseRays = true;
        logger().info("Parallel explosions: {}  |  Sampling: {}", enabled ? "ON" : "OFF", samplingQualityText());
    }

    @Override
    protected JsonObject write() {
        JsonObject json = new JsonObject();
        json.addProperty("enabled", enabled);
        json.addProperty("samplingFactor", samplingFactor);
        json.addProperty("rayLookup", rayLookup);
        json.addProperty("adaptiveRays", adaptiveRays);
        json.addProperty("preciseRays", preciseRays);
        return json;
    }

    private String samplingQualityText() { return getSamplingQuality(); }

    // ── static accessors ─────────────────────────

    public static boolean isEnabled() { return INSTANCE.loaded && INSTANCE.enabled; }
    public static void setEnabled(boolean v) { INSTANCE.enabled = v; INSTANCE.save(); }

    public static float getSamplingFactor() { return INSTANCE.samplingFactor; }

    public static void setSamplingQuality(String quality) {
        INSTANCE.samplingFactor = switch (quality) {
            case "fast" -> 1.0f;
            case "aggressive" -> 0.5f;
            default -> 2.0f;
        };
        INSTANCE.save();
    }

    public static String getSamplingQuality() {
        float f = INSTANCE.samplingFactor;
        if (f >= 2.0f) return "accurate";
        if (f >= 1.0f) return "fast";
        return "aggressive";
    }

    public static boolean isRayLookup() { return INSTANCE.rayLookup; }
    public static void setRayLookup(boolean v) { INSTANCE.rayLookup = v; INSTANCE.save(); }

    public static int getAdaptiveRays() { return INSTANCE.adaptiveRays; }
    public static void setAdaptiveRays(int v) { INSTANCE.adaptiveRays = Math.max(0, Math.min(16, v)); INSTANCE.save(); }

    public static boolean isPreciseRays() { return INSTANCE.loaded && INSTANCE.preciseRays; }
    public static void setPreciseRays(boolean v) { INSTANCE.preciseRays = v; INSTANCE.save(); }

    public static void reloadConfig() { INSTANCE.reload(); }
}
