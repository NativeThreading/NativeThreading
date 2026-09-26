package com.github.uright008.benchmark.mixin;

import com.github.uright008.benchmark.AllayScene;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Allay.class)
public abstract class AllayMixin {
    @Inject(method = "pickUpItem", at = @At("RETURN"))
    private void pathbench$pickup(ServerLevel level, ItemEntity item, CallbackInfo ci) {
        AllayScene.pickup((Allay) (Object) this, item);
    }
}
