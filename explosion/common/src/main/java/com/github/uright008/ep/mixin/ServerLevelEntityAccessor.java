package com.github.uright008.ep.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the level's entity manager so the explosion capture can enumerate
 *  entity sections directly and batch-filter bounding boxes. */
@Mixin(ServerLevel.class)
public interface ServerLevelEntityAccessor {
    @Accessor("entityManager")
    PersistentEntitySectionManager<Entity> nativeThreading$entityManager();
}
