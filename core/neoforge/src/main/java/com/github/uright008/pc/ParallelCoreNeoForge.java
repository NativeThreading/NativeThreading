package com.github.uright008.pc;

import com.github.uright008.pc.command.ParallelCommandRegistration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@Mod("parallel-core")
public class ParallelCoreNeoForge {
    public ParallelCoreNeoForge(IEventBus modBus) {
        new ParallelCore().onInitialize();
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class,
                event -> ParallelCommandRegistration.register(event.getDispatcher()));
    }
}
