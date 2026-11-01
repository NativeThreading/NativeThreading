package com.github.uright008.nt.core;

import com.github.uright008.nt.core.command.NtCommandRegistration;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public class NativeThreadingCoreFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        new NativeThreadingCore().onInitialize();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                NtCommandRegistration.register(dispatcher));

        // Same jar in a client's integrated server: release worker pools when the
        // single-player session ends instead of leaving them alive between worlds.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> NativeThreadingCore.onServerStopped());
    }
}
