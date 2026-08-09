package com.github.uright008.ep;

import com.github.uright008.pc.ParallelThreadPool;
import com.github.uright008.pc.ParallelWorker;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Main-thread entity damage pipeline: capture snapshots with a vanilla-ordered
 *  spatial query, compute damage on the worker pool (serial retrace on
 *  failure), apply on the main thread. Pure computation over an
 *  {@link ExplosionContext} plus the flat view built by
 *  {@link ExplosionBlockStage}. */
public final class ExplosionEntityStage {

    // Reusable capture lists. Every explosion fills and drains them within one
    // serial tick (capture → worker join → apply), so clearing and refilling
    // the same instances avoids allocating and repeatedly growing two
    // ArrayList per blast (~250k add + grow per profile under TNT chains).
    // Static (not per-ServerExplosion) so the reuse survives across blasts —
    // each explosion allocates a fresh ServerExplosion, so an instance field
    // would re-initialise the lists and negate the reuse. 4096 pre-sizes the
    // 2272-entity benchmark, so the lists never grow.
    private static final List<ExplosionEntityDamageComputer.EntityDamageSnapshot> CAPTURE_SNAPSHOTS = new ArrayList<>(4096);
    private static final List<Entity> CAPTURE_REFS = new ArrayList<>(4096);

    private static final Logger LOGGER = LoggerFactory.getLogger("native-threading:explosion:entity");
    private static final AtomicLong PARALLEL_ENTITY_PATHS = new AtomicLong();
    private static final AtomicLong ENTITY_WORKER_BATCHES = new AtomicLong();
    private static final AtomicLong ENTITY_FALLBACKS = new AtomicLong();

    private ExplosionEntityStage() {}

    /** Applies damage and knockback for this explosion. Returns true when the
     *  parallel path handled it (the caller then cancels vanilla
     *  hurtEntities). */
    public static boolean apply(ExplosionContext ctx, WorldReadViewImpl worldView) {
        if (ctx.radius() < 1.0E-5F) return true;

        float doubleRadius = ctx.radius() * 2.0F;
        int x0 = Mth.floor(ctx.center().x - doubleRadius - 1.0);
        int x1 = Mth.floor(ctx.center().x + doubleRadius + 1.0);
        int y0 = Mth.floor(ctx.center().y - doubleRadius - 1.0);
        int y1 = Mth.floor(ctx.center().y + doubleRadius + 1.0);
        int z0 = Mth.floor(ctx.center().z - doubleRadius - 1.0);
        int z1 = Mth.floor(ctx.center().z + doubleRadius + 1.0);

        final float dr = doubleRadius;
        final double centerX = ctx.center().x;
        final double centerY = ctx.center().y;
        final double centerZ = ctx.center().z;
        CapturedDamage captured;
        try {
            captured = captureEntityDamageSnapshots(ctx, worldView, x0, y0, z0, x1, y1, z1, dr);
        } catch (RuntimeException e) {
            // Capture is main-thread vanilla calls; a failure here has no
            // usable snapshots, so vanilla hurtEntities must run.
            ENTITY_FALLBACKS.incrementAndGet();
            LOGGER.error("Explosion entity capture failed; falling back to vanilla", e);
            return false;
        }
        List<ExplosionEntityDamageComputer.EntityDamageSnapshot> snapshots = captured.snapshots();
        List<Entity> refs = captured.refs();
        if (snapshots.isEmpty()) return true;
        try {
            ENTITY_WORKER_BATCHES.incrementAndGet();
            List<ExplosionEntityDamageComputer.EntityDamageResult> results =
                    ParallelWorker.mapBatched(ParallelThreadPool.getPool("Explosion"), snapshots,
                            snapshot -> ExplosionEntityDamageComputer.computeEntityDamage(
                                    snapshot, centerX, centerY, centerZ, dr, worldView),
                            ParallelWorker.autoBatchSize(snapshots.size()), 5);
            for (int i = 0; i < results.size(); i++) {
                ExplosionEntityDamageComputer.EntityDamageResult r = results.get(i);
                if (r != null) applyEntityDamage(r, refs.get(i), ctx);
            }
        } catch (RuntimeException e) {
            // Workers failed — the snapshots captured on the main thread are
            // still valid, so compute them serially instead of re-running
            // vanilla hurtEntities (which would re-scan the entity sections).
            ENTITY_FALLBACKS.incrementAndGet();
            LOGGER.error("Explosion entity workers failed; computing damage serially", e);
            for (int i = 0; i < snapshots.size(); i++) {
                ExplosionEntityDamageComputer.EntityDamageResult r = ExplosionEntityDamageComputer.computeEntityDamage(
                        snapshots.get(i), centerX, centerY, centerZ, dr, worldView);
                if (r != null) applyEntityDamage(r, refs.get(i), ctx);
            }
        }

        logEntityPathCounters();
        return true;
    }

    /** Snapshots plus the capture-time entity references, parallel lists.
     *  Applying by reference (not by ID re-lookup) matches vanilla
     *  hurtEntities, which iterates its collected list and still damages an
     *  entity that an earlier hit in the same blast removed. */
    private record CapturedDamage(
            List<ExplosionEntityDamageComputer.EntityDamageSnapshot> snapshots,
            List<Entity> refs) {}

    private static CapturedDamage captureEntityDamageSnapshots(
            ExplosionContext ctx, WorldReadViewImpl worldView,
            int x0, int y0, int z0, int x1, int y1, int z1, float doubleRadius) {
        AABB box = new AABB(x0, y0, z0, x1, y1, z1);
        double radiusSquare = (double) doubleRadius * doubleRadius;
        ServerExplosion self = ctx.self();
        final boolean isDefaultCalc = ctx.damageCalculator().getClass() == ExplosionDamageCalculator.class;

        // Context-free flat shapes are vanilla-exact for 99.9% of blocks. Only
        // scaffolding and powder snow vary their collision shape by the querying
        // entity; when either is present in the blast box, exposure must be
        // computed with the real entity context (vanilla-exact) per hit entity.
        final boolean needsEntityContext = hasEntityContextBlocks(worldView);

        List<ExplosionEntityDamageComputer.EntityDamageSnapshot> snapshots = CAPTURE_SNAPSHOTS;
        List<Entity> refs = CAPTURE_REFS;
        snapshots.clear();
        refs.clear();
        // Single pass over the entity sections: the predicate runs in place of
        // vanilla's NO_SPECTATORS selector, so the spatial query and the
        // snapshot capture share one traversal (vanilla's getEntities box →
        // spectator → hurtEntities ignoreExplosion → distance order is
        // preserved, all on the same entities). The predicate always returns
        // false, so no intermediate candidate list is materialised.
        ctx.level().getEntities(ctx.source(), box, entity -> {
            if (entity.isSpectator()) return false;
            if (entity.ignoreExplosion(self)) return false;
            int entityId = entity.getId();
            double feetX = entity.getX(), feetY = entity.getY(), feetZ = entity.getZ();
            if (entity.distanceToSqr(ctx.center()) > radiusSquare) return false;

            refs.add(entity);
            AABB bb = entity.getBoundingBox();
            boolean shouldDamage;
            float knockbackMultiplier;
            boolean isPrimedTnt;
            float exposure = 0.0F;
            boolean exposurePreset = false;
            if (needsEntityContext) {
                shouldDamage = isDefaultCalc
                        ? true : ctx.damageCalculator().shouldDamageEntity(self, entity);
                knockbackMultiplier = isDefaultCalc
                        ? 1.0F : ctx.damageCalculator().getKnockbackMultiplier(entity);
                isPrimedTnt = entity instanceof PrimedTnt;
                exposure = computeContextAwareExposure(
                        entity, ctx.center().x, ctx.center().y, ctx.center().z);
                exposurePreset = true;
            } else if (isDefaultCalc) {
                shouldDamage = true;
                knockbackMultiplier = 1.0F;
                isPrimedTnt = entity instanceof PrimedTnt;
            } else {
                shouldDamage = ctx.damageCalculator().shouldDamageEntity(self, entity);
                knockbackMultiplier = ctx.damageCalculator().getKnockbackMultiplier(entity);
                isPrimedTnt = entity instanceof PrimedTnt;
            }
            double eyeY = isPrimedTnt ? feetY : feetY + entity.getEyeHeight();
            // Captured on the main thread so the worker applies it in the exact
            // vanilla product order (1-dist)*exposure*kbMult*(1-res).
            double kbRes = entity instanceof LivingEntity living
                    ? living.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE)
                    : 0.0;

            snapshots.add(new ExplosionEntityDamageComputer.EntityDamageSnapshot(entityId,
                    feetX, feetY, feetZ, eyeY,
                    bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ,
                    shouldDamage, knockbackMultiplier, exposure, exposurePreset,
                    kbRes));
            return false;
        });
        return new CapturedDamage(snapshots, refs);
    }

    private static void logEntityPathCounters() {
        long paths = PARALLEL_ENTITY_PATHS.incrementAndGet();
        if ((paths & (paths - 1)) == 0) {
            LOGGER.info("Explosion entity paths: active={}, workerBatches={}, fallbacks={}",
                    paths, ENTITY_WORKER_BATCHES.get(), ENTITY_FALLBACKS.get());
        }
    }

    // Apply target with the entity/context bound via main-thread fields — the
    // ExplosionEntityApplication.Target callbacks carry no entity parameter, so
    // the stage sets both before each apply. Serial on the main thread.
    private static ExplosionContext applyCtx;
    private static Entity applyEntity;

    private static final ExplosionEntityApplication.Target APPLY_TARGET = new ExplosionEntityApplication.Target() {
        @Override
        public void hurt(float damage) {
            applyEntity.hurtServer(applyCtx.level(), applyCtx.damageSource(), damage);
        }

        @Override
        public void push(Vec3 knockback) {
            applyEntity.push(knockback);
        }

        @Override
        public void bookkeep(Vec3 knockback) {
            if (applyEntity.getType().builtInRegistryHolder().is(EntityTypeTags.REDIRECTABLE_PROJECTILE)
                    && applyEntity instanceof Projectile projectile) {
                projectile.setOwner(applyCtx.damageSource().getEntity());
            } else if (applyEntity instanceof Player player && !player.isSpectator()
                    && (!player.isCreative() || !player.getAbilities().flying)) {
                applyCtx.hitPlayers().put(player, knockback);
            }
        }

        @Override
        public void onExplosionHit() {
            applyEntity.onExplosionHit(applyCtx.source());
        }
    };

    private static void applyEntityDamage(ExplosionEntityDamageComputer.EntityDamageResult result, Entity entity,
                                          ExplosionContext ctx) {
        // Uses the capture-time reference, not a by-ID re-lookup — vanilla
        // iterates its collected entity list and applies to every member even
        // if an earlier hit removed it.
        applyCtx = ctx;
        applyEntity = entity;
        ExplosionEntityApplication.apply(result, APPLY_TARGET);
    }

    // ── Main-thread entity helpers ───────────────────────────────────────────

    private static boolean isEntityContextBlock(net.minecraft.world.level.block.Block block) {
        return block instanceof net.minecraft.world.level.block.ScaffoldingBlock
                || block instanceof net.minecraft.world.level.block.PowderSnowBlock
                || block instanceof net.minecraft.world.level.block.LiquidBlock;
    }

    /** True if the flat view contains scaffolding, powder snow, or a liquid —
     *  blocks whose collision shape depends on the querying entity context.
     *  Scaffolding/powder-snow vary their solid shape; LiquidBlock returns a
     *  non-empty fluid-collision shape for a living entity context but empty
     *  for {@code (null, null)}, so a liquid would let exposure rays pass that
     *  vanilla clip would stop. When any is present, exposure must be computed
     *  with the real entity context (vanilla-exact). */
    private static boolean hasEntityContextBlocks(WorldReadViewImpl worldView) {
        net.minecraft.world.level.block.state.BlockState[] states = worldView.states();
        for (net.minecraft.world.level.block.state.BlockState state : states) {
            if (state != null && isEntityContextBlock(state.getBlock())) return true;
        }
        return false;
    }

    /** Vanilla-exact exposure: {@link net.minecraft.world.level.ServerExplosion#getSeenPercent}
     *  with the real entity context (scaffolding isAbove/isDescending, powder-snow
     *  fallDistance/boots are all resolved from the entity). Main-thread only —
     *  touches the entity, never run on workers. */
    private static float computeContextAwareExposure(
            Entity entity, double centerX, double centerY, double centerZ) {
        return net.minecraft.world.level.ServerExplosion.getSeenPercent(
                new Vec3(centerX, centerY, centerZ), entity);
    }
}
