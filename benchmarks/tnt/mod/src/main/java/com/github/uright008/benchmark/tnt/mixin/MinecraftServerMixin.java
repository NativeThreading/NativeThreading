package com.github.uright008.benchmark.tnt.mixin;

import com.github.uright008.benchmark.tnt.TntObservationStage;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "tickServer", at = @At("HEAD"))
    private void tntbench$start(BooleanSupplier haveTime, CallbackInfo ci) {
        TntObservationStage.tickStart((MinecraftServer) (Object) this);
    }

    @Inject(method = "tickServer", at = @At("RETURN"))
    private void tntbench$end(BooleanSupplier haveTime, CallbackInfo ci) {
        TntObservationStage.tickEnd((MinecraftServer) (Object) this);
    }
}
