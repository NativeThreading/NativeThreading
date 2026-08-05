package com.github.uright008.pc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * File-based {@link ConfigStorage} for production use.
 * <p>Persists to {@code config/nt.json} with atomic writes.</p>
 */
public final class FileConfigStorage implements ConfigStorage {

    public static final FileConfigStorage INSTANCE = new FileConfigStorage();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = LoggerFactory.getLogger("parallel-config");

    private FileConfigStorage() {}

    @Override
    public JsonObject loadRoot() {
        Path path = configPath();
        if (Files.exists(path)) {
            try {
                String raw = Files.readString(path);
                return GSON.fromJson(raw, JsonObject.class);
            } catch (Exception e) {
                LOGGER.warn("Failed to load config file, using defaults", e);
            }
        }
        return null;
    }

    @Override
    public void saveRoot(JsonObject root) {
        try {
            Path path = configPath();
            Files.createDirectories(path.getParent());
            Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
            try {
                Files.writeString(tempPath, GSON.toJson(root));
                try {
                    Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException ignoredAtomicMove) {
                    try {
                        Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException fallbackMoveException) {
                        try { Files.deleteIfExists(tempPath); } catch (IOException ignored) {}
                        throw fallbackMoveException;
                    }
                }
            } catch (IOException writeOrMoveException) {
                try { Files.deleteIfExists(tempPath); } catch (IOException ignored) {}
                throw writeOrMoveException;
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to save config", e);
        }
    }

    private static Path configPath() {
        return Path.of("config", "nt.json");
    }
}
