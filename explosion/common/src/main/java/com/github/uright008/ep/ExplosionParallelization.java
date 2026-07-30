package com.github.uright008.ep;

import com.github.uright008.pc.command.ParallelCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExplosionParallelization {
    private static final Logger LOGGER = LoggerFactory.getLogger("explosion");

    public void onInitialize() {
        LOGGER.info("Explosion Parallelization initializing...");
        ExplosionHelper.initFullCubeCache();
        ExplosionParallelConfig.init();
        ParallelCommand.registerSubCommand(new ExplosionParallelCommand());
        LOGGER.info("Explosion Parallelization ready — use /parallel explosion");
    }
}
