package com.github.uright008.pc;

import com.github.uright008.pc.command.ParallelCommand;
import com.github.uright008.pc.command.ParallelCoreCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ParallelCore {
    public static final String MOD_ID = "parallel-core";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public void onInitialize() {
        ParallelCoreConfig.init();
        LOGGER.info("ParallelCore initializing — thread pool and world management ready.");

        ParallelCommand.registerSubCommand(new ParallelCoreCommand());
    }

    /**
     * Drops per-server runtime state. Worker pools are lazily recreated on next
     * use, so a client does not keep a finished single-player session's threads
     * alive between worlds. Loaders call this from their server-stopped event.
     */
    public static void onServerStopped() {
        ParallelThreadPool.shutdownAll();
        LOGGER.info("ParallelCore stopped — worker pools shut down.");
    }
}
