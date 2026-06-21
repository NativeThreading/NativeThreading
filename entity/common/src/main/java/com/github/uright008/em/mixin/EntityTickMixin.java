package com.github.uright008.em.mixin;

import com.github.uright008.em.EntityParallelConfig;
import com.github.uright008.pc.ParallelThreadPool;
import com.github.uright008.pc.ParallelWorker;
import com.github.uright008.pc.SafeLevelAccess;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.entity.EntityTickList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Parallelises the entity tick section of {@link ServerLevel#tick}.
 *
 * <p>Two-injection pattern:</p>
 * <ol>
 *   <li>{@code @Inject} at {@code push("entities")} — collects all entities
 *       via {@code ServerLevel.getAllEntities()}, buckets them by chunk-section
 *       (16×16×16), and dispatches each bucket to the {@code "EntityTick"}
 *       thread pool.</li>
 *   <li>{@code @WrapOperation} on {@code EntityTickList.forEach(Consumer)} —
 *       replaces the vanilla entity tick loop with a no-op, since all entities
 *       have already been ticked in parallel.</li>
 * </ol>
 */
@Mixin(ServerLevel.class)
public abstract class EntityTickMixin {

    @Unique
    private static final Logger LOGGER = LoggerFactory.getLogger("mc-parallel:entity");

    @Unique
    private static final Long2ObjectOpenHashMap<List<Entity>> BUCKETS = new Long2ObjectOpenHashMap<>();

    @Shadow
    public abstract Iterable<Entity> getAllEntities();

    // ── Phase 1: parallel tick at entity section entry ────────────────────

    @Inject(method = "tick", at = @At(value = "INVOKE_STRING",
            target = "Lnet/minecraft/util/profiling/ProfilerFiller;push(Ljava/lang/String;)V",
            args = "ldc=entities"))
    private void em$parallelEntityTick(CallbackInfo ci) {
        if (!EntityParallelConfig.isEnabled()) {
            return;
        }

        ServerLevel self = (ServerLevel) (Object) this;

        // Collect all entities, separating safe (parallel) from unsafe (main thread)
        List<Entity> safeEntities = new ArrayList<>();
        List<Entity> unsafeEntities = new ArrayList<>();
        for (Entity entity : this.getAllEntities()) {
            if (entity.isRemoved()) continue;
            // Entities that spawn/remove other entities during tick() must
            // stay on the main thread to avoid EntityLookup corruption.
            if (entity instanceof PrimedTnt || entity instanceof Projectile) {
                unsafeEntities.add(entity);
            } else {
                safeEntities.add(entity);
            }
        }

        // Tick safe entities in parallel
        if (!safeEntities.isEmpty()) {
            // Bucket by chunk-section (16×16×16)
            BUCKETS.clear();
            for (Entity entity : safeEntities) {
                long key = SectionPos.asLong(
                        SectionPos.blockToSectionCoord(entity.blockPosition().getX()),
                        SectionPos.blockToSectionCoord(entity.blockPosition().getY()),
                        SectionPos.blockToSectionCoord(entity.blockPosition().getZ())
                );
                List<Entity> bucket = BUCKETS.get(key);
                if (bucket == null) {
                    bucket = new ArrayList<>();
                    BUCKETS.put(key, bucket);
                }
                bucket.add(entity);
            }

            List<List<Entity>> bucketLists = new ArrayList<>(BUCKETS.values());

            try {
                SafeLevelAccess.runSafe(() -> {
                    ParallelWorker.forEach(
                            ParallelThreadPool.getPool("EntityTick"),
                            bucketLists,
                            bucket -> {
                                for (Entity e : bucket) {
                                    if (!e.isRemoved()) {
                                        self.tickNonPassenger(e);
                                    }
                                }
                            },
                            EntityParallelConfig.tickTimeoutSeconds()
                    );
                });
            } catch (RuntimeException ex) {
                LOGGER.error("Entity parallel tick failed; falling back to vanilla for safe entities", ex);
                safeEntities.forEach(e -> { if (!e.isRemoved()) try { self.tickNonPassenger(e); } catch (Throwable t) { LOGGER.error("tick fallback", t); } });
            }
        }

        // Tick unsafe entities on main thread (sequentially)
        for (Entity entity : unsafeEntities) {
            if (!entity.isRemoved()) {
                self.tickNonPassenger(entity);
            }
        }
    }

    // ── Phase 2: skip vanilla entity loop ─────────────────────────────────

    /**
     * Replaces the vanilla {@code entityTickList.forEach(consumer)} with
     * a no-op.  All entities were already ticked during Phase 1.
     */
    @WrapOperation(method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"))
    private void em$skipVanillaEntityLoop(
            EntityTickList list,
            Consumer<Entity> action,
            Operation<Void> original) {

        if (EntityParallelConfig.isEnabled()) {
            return; // no-op — entities already ticked in parallel
        }
        original.call(list, action);
    }
}
