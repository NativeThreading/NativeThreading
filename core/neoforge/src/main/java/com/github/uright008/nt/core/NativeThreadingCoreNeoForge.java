package com.github.uright008.nt.core;

import com.github.uright008.nt.core.command.NtCommandRegistration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@Mod("native_threading_core")
public class NativeThreadingCoreNeoForge {
    public NativeThreadingCoreNeoForge(IEventBus modBus) {
        new NativeThreadingCore().onInitialize();
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class,
                event -> NtCommandRegistration.register(event.getDispatcher()));
    }
}
