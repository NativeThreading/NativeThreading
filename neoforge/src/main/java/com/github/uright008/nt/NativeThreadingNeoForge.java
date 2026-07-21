package com.github.uright008.nt;

import com.github.uright008.ep.ExplosionParallelization;
import com.github.uright008.hp.HopperParallelization;
import com.github.uright008.pc.ParallelCore;
import com.github.uright008.rp.RedstoneParallelization;
import com.github.uright008.vec.Vectorial;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod("native_threading")
public class NativeThreadingNeoForge {

    public NativeThreadingNeoForge(IEventBus modBus) {
        Vectorial.init();

        new ParallelCore().onInitialize();
        new ExplosionParallelization().onInitialize();
        new HopperParallelization().onInitialize();
        new RedstoneParallelization().onInitialize();
    }
}
