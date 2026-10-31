package com.github.uright008.nt;

import com.github.uright008.ep.ExplosionParallelization;
import com.github.uright008.pc.ParallelCore;
import com.github.uright008.pc.command.ParallelCommandRegistration;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public class NativeThreading implements ModInitializer {
    public static final String MOD_ID = "native-threading";

    @Override
    public void onInitialize() {
        new ParallelCore().onInitialize();
        new ExplosionParallelization().onInitialize();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ParallelCommandRegistration.register(dispatcher));

        // The same jar runs in a client's integrated server; a stopped server means the
        // single-player session is over and its world must not stay reachable from statics.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ParallelCore.onServerStopped();
            ExplosionParallelization.onServerStopped();
        });
    }
}
