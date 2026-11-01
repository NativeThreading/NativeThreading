package com.github.uright008.ep;

import com.github.uright008.pc.command.ParallelCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExplosionParallelization {
    private static final Logger LOGGER = LoggerFactory.getLogger("explosion");

    public void onInitialize() {
        LOGGER.info("Explosion Parallelization initializing...");
        ExplosionParallelConfig.init();
        ParallelCommand.registerSubCommand(new ExplosionParallelCommand());
        LOGGER.info("Explosion Parallelization ready — use /parallel explosion");
    }

    /**
     * Drops the pipeline's cross-blast static state. Called from the loaders'
     * server-stopped hooks: dedicated servers end the process right after, but a
     * client may leave a single-player world and open another one, and the old
     * world must not stay reachable from the module's static caches.
     */
    public static void onServerStopped() {
        ExplosionChunkGridCache.clear();
        ExplosionEntityStage.clearStaticState();
        ExplosionVisibilitySnapshot.end();
    }
}
