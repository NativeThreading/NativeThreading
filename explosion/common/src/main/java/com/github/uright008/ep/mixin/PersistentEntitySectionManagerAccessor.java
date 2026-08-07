package com.github.uright008.ep.mixin;

import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the section storage that holds all live entity sections. */
@Mixin(PersistentEntitySectionManager.class)
public interface PersistentEntitySectionManagerAccessor<T extends EntityAccess> {
    @Accessor("sectionStorage")
    EntitySectionStorage<T> nativeThreading$sectionStorage();
}
