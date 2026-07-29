package com.github.uright008.pc;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelConfigTest {

    private TestConfig config;

    @BeforeEach
    void setUp() {
        config = new TestConfig(ConfigStorage.inMemory());
        config.initialize();
    }

    @Test
    void load_defaultValues() {
        assertTrue(config.loaded);
        assertEquals("default", config.stringValue);
        assertEquals(42, config.intValue);
        assertFalse(config.boolValue);
    }

    @Test
    void load_customValues() {
        TestConfig custom = new TestConfig(ConfigStorage.inMemory());
        custom.initialize();
        custom.stringValue = "custom";
        custom.intValue = 100;
        custom.boolValue = true;
        custom.save();

        TestConfig reloaded = new TestConfig(custom.storage);
        reloaded.initialize();

        assertEquals("custom", reloaded.stringValue);
        assertEquals(100, reloaded.intValue);
        assertTrue(reloaded.boolValue);
    }

    @Test
    void reload_updatesValues() {
        config.stringValue = "updated";
        config.intValue = 99;
        config.save();

        config.reload();

        assertEquals("updated", config.stringValue);
        assertEquals(99, config.intValue);
    }

    @Test
    void save_persistsValues() {
        config.stringValue = "persisted";
        config.intValue = 77;
        config.save();

        TestConfig reloaded = new TestConfig(config.storage);
        reloaded.initialize();

        assertEquals("persisted", reloaded.stringValue);
        assertEquals(77, reloaded.intValue);
    }

    private static class TestConfig extends ParallelConfig {

        String stringValue;
        int intValue;
        boolean boolValue;
        final ConfigStorage storage;

        TestConfig(ConfigStorage storage) {
            super("test", storage);
            this.storage = storage;
        }

        @Override
        protected void applyDefaults() {
            stringValue = "default";
            intValue = 42;
            boolValue = false;
        }

        @Override
        protected void read(JsonObject json) {
            if (json.has("stringValue")) stringValue = json.get("stringValue").getAsString();
            if (json.has("intValue")) intValue = json.get("intValue").getAsInt();
            if (json.has("boolValue")) boolValue = json.get("boolValue").getAsBoolean();
        }

        @Override
        protected JsonObject write() {
            JsonObject json = new JsonObject();
            json.addProperty("stringValue", stringValue);
            json.addProperty("intValue", intValue);
            json.addProperty("boolValue", boolValue);
            return json;
        }
    }
}
