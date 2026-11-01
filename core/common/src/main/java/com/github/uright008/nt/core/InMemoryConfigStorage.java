package com.github.uright008.nt.core;

import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * In-memory {@link ConfigStorage} for testing.
 * <p>Thread-safe via {@code synchronized} on all read/write operations.
 * Multiple sections coexist in the same store.</p>
 */
public final class InMemoryConfigStorage implements ConfigStorage {

    private final Map<String, JsonObject> store = new HashMap<>();

    public InMemoryConfigStorage() {}

    @Override
    public synchronized JsonObject loadRoot() {
        // Return a deep copy to prevent mutation of stored state
        JsonObject stored = store.get("root");
        if (stored == null) return null;
        // Simple deep copy via re-serialization for test safety
        try {
            return com.google.gson.JsonParser.parseString(stored.toString()).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public synchronized void saveRoot(JsonObject root) {
        // Store a deep copy
        try {
            JsonObject copy = com.google.gson.JsonParser.parseString(root.toString()).getAsJsonObject();
            store.put("root", copy);
        } catch (Exception e) {
            store.put("root", root); // fallback: store original
        }
    }

    /** Clear all stored config data. Call between tests. */
    public synchronized void clear() {
        store.clear();
    }

    /** Returns true if this storage has any persisted data. */
    public synchronized boolean isEmpty() {
        return store.isEmpty();
    }
}
