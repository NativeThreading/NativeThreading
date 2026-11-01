package com.github.uright008.nt;

import com.github.uright008.nt.explosion.ExplosionModule;
import com.github.uright008.nt.core.NativeThreadingCore;
import com.github.uright008.nt.core.command.NtCommandRegistration;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public class NativeThreading implements ModInitializer {
    public static final String MOD_ID = "native-threading";

    @Override
    public void onInitialize() {
        new NativeThreadingCore().onInitialize();
        new ExplosionModule().onInitialize();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                NtCommandRegistration.register(dispatcher));

        // The same jar runs in a client's integrated server; a stopped server means the
        // single-player session is over and its world must not stay reachable from statics.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            NativeThreadingCore.onServerStopped();
            ExplosionModule.onServerStopped();
        });
    }
}
