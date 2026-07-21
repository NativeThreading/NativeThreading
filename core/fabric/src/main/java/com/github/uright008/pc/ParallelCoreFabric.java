package com.github.uright008.pc;

import com.github.uright008.pc.command.ParallelCommandRegistration;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

public class ParallelCoreFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        new ParallelCore().onInitialize();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ParallelCommandRegistration.register(dispatcher));
    }
}
