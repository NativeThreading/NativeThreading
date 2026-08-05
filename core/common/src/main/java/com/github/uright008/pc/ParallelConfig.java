package com.github.uright008.pc;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Set;

/**
 * Base class for mod configs persisted as sections in the shared
 * {@code config/nt.json} file.
 *
 * <p>Each subclass provides a section key (e.g. {@code "physics-parallelization"})
 * and reads/writes its settings as a JSON object under that key.  All sections
 * live in a single file, so the entire config can be inspected and edited
 * in one place.</p>
 *
 * <p>Config loading is <b>lazy</b>: the constructor only stores the section
 * key; no disk I/O happens until {@link #initialize()} or the first {@link #reload()}
 * is called.  This means an un-initialised subsystem does not pollute the
 * config file with default values.</p>
 *
 * <pre>
 * // nt.json
 * {
 *   "physics-parallelization": { "enabled": true },
 *   "explosion-parallelization": { "enabled": true, "samplingFactor": 2.0 }
 * }
 * </pre>
 *
 * <p>Usage:</p>
 * <pre>
 * public class MyConfig extends ParallelConfig {
 *     public MyConfig() { super("my-section"); }   // no I/O here
 *
 *     private volatile boolean enabled;
 *
 *     public static boolean isEnabled() { return INSTANCE.loaded && INSTANCE.enabled; }
 *     public static void init() { INSTANCE.initialize(); }
 *
 *     &#064;Override protected void read(JsonObject json) { ... }
 *     &#064;Override protected JsonObject write() { ... }
 *     &#064;Override protected void applyDefaults() { enabled = true; ... }
 * }
 * </pre>
 */
public abstract class ParallelConfig {

    private final String sectionKey;
    private final Logger logger;
    private final ConfigStorage storage;
    protected volatile boolean loaded;

    /**
     * Creates a config section with the default file-based storage.
     * Equivalent to {@code ParallelConfig(sectionKey, ConfigStorage.fileBased())}.
     */
    protected ParallelConfig(String sectionKey) {
        this(sectionKey, ConfigStorage.fileBased());
    }

    /**
     * Creates a config section with a custom {@link ConfigStorage}.
     * <p>Use {@link ConfigStorage#inMemory()} for testing without filesystem I/O.</p>
     */
    protected ParallelConfig(String sectionKey, ConfigStorage storage) {
        this.sectionKey = sectionKey;
        this.logger = LoggerFactory.getLogger("parallel-config:" + sectionKey);
        this.storage = storage;
    }

    // ── subclasses implement these ───────────────

    /** Populate fields from a loaded JSON object (the section's subtree). */
    protected abstract void read(JsonObject json);

    /** Serialize current field values into a JSON object (the section's subtree). */
    protected abstract JsonObject write();

    /**
     * Called when no config section exists yet.
     * Override to set field defaults.
     * <p><b>Do NOT call {@link #save()} here</b> — the caller handles persistence.</p>
     */
    protected void applyDefaults() {}

    /**
     * Load config from disk if not already loaded, then persist.
     * Called once from the subsystem {@code onInitialize()}.
     */
    private final Object fileLock = new Object();

    public void initialize() {
        if (loaded) return;
        synchronized (fileLock) {
            if (loaded) return;
            reload();
            save();
            loaded = true;
        }
    }

    public void reload() {
        synchronized (fileLock) {
            JsonObject root = storage.loadRoot();
            if (root != null && root.has(sectionKey)) {
                try {
                    read(root.getAsJsonObject(sectionKey));
                    logger.info("Config loaded for section '{}'", sectionKey);
                    return;
                } catch (Exception e) {
                    logger.warn("Failed to parse config section '{}', using defaults", sectionKey, e);
                }
            }
            applyDefaults();
            logger.info("Defaults applied for section '{}'", sectionKey);
        }
    }

    public void save() {
        synchronized (fileLock) {
            try {
                JsonObject root = storage.loadRoot();
                if (root == null) root = new JsonObject();
                root.add(sectionKey, write());
                storage.saveRoot(root);
            } catch (Exception e) {
                logger.warn("Failed to save config section '{}'", sectionKey, e);
            }
        }
    }

    public Path getPath() {
        if (storage instanceof FileConfigStorage) {
            return Path.of("config", "nt.json");
        }
        return Path.of("config", "nt.json");
    }

    protected Logger logger() {
        return logger;
    }

    private static final Set<String> KNOWN_SECTIONS = Set.of(
            "parallel-core", "explosion", "redstone"
    );

    public static void validateConfigSections() {
        JsonObject root = ConfigStorage.fileBased().loadRoot();
        if (root == null) return;
        Logger log = LoggerFactory.getLogger("parallel-config");
        for (String key : root.keySet()) {
            if (!KNOWN_SECTIONS.contains(key)) {
                log.warn("Unrecognized config section '{}' in nt.json — this section will be ignored", key);
            }
        }
    }
}
