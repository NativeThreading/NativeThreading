package com.github.uright008.nt.explosion;

import com.github.uright008.nt.core.command.NtCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExplosionModule {
    private static final Logger LOGGER = LoggerFactory.getLogger("explosion");

    public void onInitialize() {
        LOGGER.info("Explosion module initializing...");
        ExplosionConfig.init();
        NtCommands.registerSubCommand(new ExplosionCommand());
        LOGGER.info("Explosion module ready — use /nt explosion");
    }

    /**
     * Drops the pipeline's cross-blast static state. Called from the loaders'
     * server-stopped hooks: dedicated servers end the process right after, but a
     * client may leave a single-player world and open another one, and the old
     * world must not stay reachable from the module's static caches.
     */
    public static void onServerStopped() {
        ExplosionStats.reset();
        ExplosionChunkGridCache.clear();
        ExplosionEntityStage.clearStaticState();
        ExplosionVisibilitySnapshot.end();
    }
}
