package com.github.uright008.nt;

import com.github.uright008.ep.ExplosionParallelization;
import com.github.uright008.pc.ParallelCore;
import com.github.uright008.pc.command.ParallelCommandRegistration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

@Mod("native_threading")
public class NativeThreadingNeoForge {

    public NativeThreadingNeoForge(IEventBus modBus) {
        new ParallelCore().onInitialize();
        new ExplosionParallelization().onInitialize();

        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class,
                event -> ParallelCommandRegistration.register(event.getDispatcher()));

        // The same jar runs in a NeoForge client's integrated server; a stopped server
        // means the single-player session is over and its world must not stay reachable.
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> {
            ParallelCore.onServerStopped();
            ExplosionParallelization.onServerStopped();
        });
    }
}
