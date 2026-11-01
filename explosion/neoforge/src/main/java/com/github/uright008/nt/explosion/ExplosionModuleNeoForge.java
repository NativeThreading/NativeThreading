package com.github.uright008.nt.explosion;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod("explosion")
public class ExplosionModuleNeoForge {
    public ExplosionModuleNeoForge(IEventBus modBus) {
        new ExplosionModule().onInitialize();
    }
}
