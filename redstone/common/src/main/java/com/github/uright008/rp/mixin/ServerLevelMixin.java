package com.github.uright008.rp.mixin;

import com.github.uright008.rp.DiodeTickBatcher;
import com.github.uright008.rp.RedstoneParallelConfig;
import com.github.uright008.rp.RedstoneWireHelper;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void beforeTick(CallbackInfo ci) {
        RedstoneWireHelper.clearProcessed();
    }

    @Inject(method = "tick", at = @At(value = "INVOKE_STRING",
            target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
            args = "ldc=raid"), require = 0)
    private void afterBlockTicks(CallbackInfo ci) {
        if (RedstoneParallelConfig.isDiodeEnabled()) {
            DiodeTickBatcher.flush((ServerLevel) (Object) this);
        }
    }
}
