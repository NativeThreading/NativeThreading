package com.github.uright008.pc.mixin;

import com.github.uright008.pc.ParallelCore;
import com.github.uright008.pc.ParallelCoreHorizon;
import com.github.uright008.pc.command.ParallelCommandRegistration;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ignite mod initialization. Fires once during server startup.
 * Calls ParallelCore.onInitialize() and registers /parallel commands.
 */
@Mixin(MinecraftServer.class)
public class IgniteParallelCoreInit {

    @Inject(method = "runServer", at = @At("HEAD"))
    private void onServerStart(CallbackInfo ci) {
        ParallelCoreHorizon.init();
        ParallelCommandRegistration.register(((MinecraftServer) (Object) this).getCommands().getDispatcher());
    }
}
