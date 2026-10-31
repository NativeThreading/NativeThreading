package com.github.uright008.ep;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public class ExplosionParallelizationFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        new ExplosionParallelization().onInitialize();

        // A client's integrated server stops when the single-player world is left;
        // drop the cross-blast statics so the old world is not retained.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ExplosionParallelization.onServerStopped());
    }
}
