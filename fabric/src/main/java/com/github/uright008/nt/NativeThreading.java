package com.github.uright008.nt;

import com.github.uright008.ep.ExplosionParallelization;
import com.github.uright008.hp.HopperParallelization;
import com.github.uright008.pc.ParallelCore;
import com.github.uright008.pc.command.ParallelCommandRegistration;
import com.github.uright008.rp.RedstoneParallelization;
import com.github.uright008.vec.Vectorial;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

public class NativeThreading implements ModInitializer {
    public static final String MOD_ID = "native-threading";

    @Override
    public void onInitialize() {
        Vectorial.init();
        new ParallelCore().onInitialize();
        new ExplosionParallelization().onInitialize();
        new HopperParallelization().onInitialize();
        new RedstoneParallelization().onInitialize();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ParallelCommandRegistration.register(dispatcher));
    }
}
