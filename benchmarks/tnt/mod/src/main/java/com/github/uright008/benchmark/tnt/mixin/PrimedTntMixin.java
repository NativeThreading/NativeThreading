package com.github.uright008.benchmark.tnt.mixin;

import com.github.uright008.benchmark.tnt.TntObservationStage;
import net.minecraft.world.entity.item.PrimedTnt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PrimedTnt.class)
public abstract class PrimedTntMixin {
    @Inject(method = "explode", at = @At("RETURN"))
    private void tntbench$exploded(CallbackInfo ci) {
        TntObservationStage.exploded((PrimedTnt) (Object) this);
    }
}
