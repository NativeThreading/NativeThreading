package com.github.uright008.nt;

import com.github.uright008.pc.command.ParallelCommandRegistration;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = "native_threading")
public class NativeThreadingCommands {

    @SubscribeEvent
    private static void onRegisterCommands(RegisterCommandsEvent event) {
        ParallelCommandRegistration.register(event.getDispatcher());
    }
}
