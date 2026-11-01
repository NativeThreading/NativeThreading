package com.github.uright008.nt.core;

import com.github.uright008.nt.core.command.NtCommands;
import com.github.uright008.nt.core.command.CoreCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NativeThreadingCore {
    public static final String MOD_ID = "native-threading-core";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public void onInitialize() {
        CoreConfig.init();
        LOGGER.info("NativeThreadingCore initializing — thread pool and world management ready.");

        NtCommands.registerSubCommand(new CoreCommand());
    }

    /**
     * Drops per-server runtime state. Worker pools are lazily recreated on next
     * use, so a client does not keep a finished single-player session's threads
     * alive between worlds. Loaders call this from their server-stopped event.
     */
    public static void onServerStopped() {
        NtThreadPool.shutdownAll();
        LOGGER.info("NativeThreadingCore stopped — worker pools shut down.");
    }
}
