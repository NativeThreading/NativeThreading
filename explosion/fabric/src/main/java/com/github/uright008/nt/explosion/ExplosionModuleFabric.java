package com.github.uright008.nt.explosion;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public class ExplosionModuleFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        new ExplosionModule().onInitialize();

        // A client's integrated server stops when the single-player world is left;
        // drop the cross-blast statics so the old world is not retained.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ExplosionModule.onServerStopped());
    }
}
