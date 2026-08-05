package com.github.uright008.pc;

import com.google.gson.JsonObject;

/**
 * Abstraction for configuration persistence.
 *
 * <p>Production uses {@link FileConfigStorage} (writes to {@code config/nt.json}).
 * Tests use {@link InMemoryConfigStorage} for isolation without filesystem I/O.</p>
 */
public interface ConfigStorage {

    /** Load the root JSON object from storage, or null if no config exists. */
    JsonObject loadRoot();

    /** Persist the root JSON object to storage. */
    void saveRoot(JsonObject root);

    /** Returns the default file-based storage (production). */
    static ConfigStorage fileBased() { return FileConfigStorage.INSTANCE; }

    /** Returns an in-memory storage (testing). */
    static ConfigStorage inMemory() { return new InMemoryConfigStorage(); }
}
