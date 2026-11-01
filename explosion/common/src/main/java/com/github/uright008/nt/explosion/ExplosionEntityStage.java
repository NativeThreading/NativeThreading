package com.github.uright008.nt.explosion;

import com.github.uright008.nt.core.NtThreadPool;
import com.github.uright008.nt.core.NtWorker;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.EntityBasedExplosionDamageCalculator;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

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
    // would re-initialise the lists and negate the reuse. 8192 pre-sizes the
    // 3670-entity sustained chamber, so the units list never grows.
    private static final List<ExplosionEntityDamageComputer.EntityDamageSnapshot> CAPTURE_UNITS = new ArrayList<>(8192);
    // Captured victim references live in a plain array with an explicit count:
    // the 93k-TNT pile captures tens of thousands of victims per blast, and
    // ArrayList.add's modCount/bounds bookkeeping showed up as a top capture
    // self-time in JFR (attributed to the inlined ArrayList.add body). The array
    // is main-thread only, grows geometrically, and keeps strong refs until the
    // next capture (same contract as the old ArrayList).
    private static Entity[] CAPTURE_REFS = new Entity[8192];
    private static int CAPTURE_REF_COUNT;
    // Per-capture memo for exact PrimedTnt: entity tags cannot change within a
    // tick and every exact PrimedTnt victim shares the same type, so the
    // POWDER_SNOW_WALKABLE_MOBS lookup is done once per blast instead of once per
    // victim (it was 18% of capture self-time on the 93k-TNT pile).
    private static boolean PRIMED_TNT_POWDER_WALKABLE;
    private static boolean PRIMED_TNT_POWDER_KNOWN;

    // Per-blast captured-victim layout: every victim keeps its own refs entry and
    // its own ordered apply push, but compatible victims share one compute unit
    // (one snapshot, one worker task, one result). unitOfVictim[victimIndex] is
    // the compute-unit index; unitIsGroup[unitIndex] marks units whose apply is
    // push-only (see captureEntityDamageSnapshots / applyGroupedDamage).
    private static int[] VICTIM_UNITS = new int[8192];
    private static boolean[] UNIT_IS_GROUP = new boolean[8192];
    private static long ENTITY_LAST_UNITS;
    private static long ENTITY_LAST_GROUPED_VICTIMS;
    private static long ENTITY_LAST_VICTIMS;

    /** Tuning parameter sizes reported by {@code /nt explosion stats}. */
    static int exposureDedupCapacity() { return EXPOSURE_DEDUP_CAPACITY; }
    static int groupCapacity() { return GROUP_CAPACITY; }
    static long lastVictims() { return ENTITY_LAST_VICTIMS; }
    static long lastUnits() { return ENTITY_LAST_UNITS; }
    static long lastGroupedVictims() { return ENTITY_LAST_GROUPED_VICTIMS; }

    // Group table for the current blast: identical victims (same feet position,
    // eye height and sampled exposure) share one compute unit. Capacity 16; past
    // it a victim simply becomes an own unit, so a blast full of distinct victims
    // cannot degrade (no per-victim allocation happens either way).
    private static final int GROUP_CAPACITY = 16;
    private static final double[] GROUP_FEET_X = new double[GROUP_CAPACITY];
    private static final double[] GROUP_FEET_Y = new double[GROUP_CAPACITY];
    private static final double[] GROUP_FEET_Z = new double[GROUP_CAPACITY];
    private static final double[] GROUP_EYE_Y = new double[GROUP_CAPACITY];
    private static final float[] GROUP_EXPOSURE = new float[GROUP_CAPACITY];
    private static final int[] GROUP_UNIT = new int[GROUP_CAPACITY];
    private static int groupSize;

    // Per-blast exposure dedup scratch (cleared at every capture, primitive only).
    // For a non-living entity whose collision context reads nothing but its own
    // AABB, feet Y and shift-down flag, vanilla getSeenPercent is a pure function
    // of (AABB, feetY, isDescending, center, world). Repeated victims - the TNT
    // pile in one spot is the motivating case - then share one live sampling
    // instead of one per entity. See exposureContextIsPositionOnly.
    private static final int EXPOSURE_DEDUP_CAPACITY = 8;
    private static final double[] EXPOSURE_DEDUP_AABB = new double[EXPOSURE_DEDUP_CAPACITY * 6];
    private static final double[] EXPOSURE_DEDUP_FEET_Y = new double[EXPOSURE_DEDUP_CAPACITY];
    private static final boolean[] EXPOSURE_DEDUP_DESCENDING = new boolean[EXPOSURE_DEDUP_CAPACITY];
    private static final float[] EXPOSURE_DEDUP_VALUE = new float[EXPOSURE_DEDUP_CAPACITY];
    private static int exposureDedupSize;

    private static final Logger LOGGER = LoggerFactory.getLogger("native-threading:explosion:entity");
    // Explosions are serial on the main thread, so plain longs are sufficient
    // and avoid the 2.4% AtomicLong CAS seen in profiles. Only the server
    // thread writes; readers (if any) see eventually-consistent values.
    private static long WORKER_ENTITY_PATHS;
    private static long ENTITY_WORKER_BATCHES;
    private static long ENTITY_FALLBACKS;

    private ExplosionEntityStage() {}

    /** Applies damage and knockback for this explosion. Returns true when the
     *  parallel path handled it (the caller then cancels vanilla
     *  hurtEntities). */
    public static boolean apply(ExplosionContext ctx, WorldReadViewImpl worldView) {
        if (ctx.radius() < 1.0E-5F) return true;
        // Diagnostics: a blast that never reaches the capture path (e.g. no
        // victims in range) must not report the previous blast's last values.
        ENTITY_LAST_VICTIMS = 0;
        ENTITY_LAST_UNITS = 0;
        ENTITY_LAST_GROUPED_VICTIMS = 0;

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
        final long captureStart = System.nanoTime();
        CapturedDamage captured;
        try {
            captured = captureEntityDamageSnapshots(ctx, worldView, x0, y0, z0, x1, y1, z1, dr);
        } catch (RuntimeException e) {
            // Capture is main-thread vanilla calls; a failure here has no
            // usable snapshots, so vanilla hurtEntities must run.
            ++ENTITY_FALLBACKS;
            ExplosionStats.recordEntitySerialFallback();
            LOGGER.error("Explosion entity capture failed; falling back to vanilla", e);
            return false;
        }
        final long captureNanos = System.nanoTime() - captureStart;
        List<ExplosionEntityDamageComputer.EntityDamageSnapshot> units = captured.units();
        Entity[] refs = captured.refs();
        final int refCount = captured.refCount();
        ENTITY_LAST_UNITS = units.size();
        ENTITY_LAST_VICTIMS = refCount;
        if (refCount == 0) {
            ExplosionStats.recordEntityStage(captureNanos, 0L, 0L, 0, 0, 0, 0, 0L);
            return true;
        }
        int[] unitOfVictim = captured.unitOfVictim();
        boolean[] unitIsGroup = captured.unitIsGroup();
        List<ExplosionEntityDamageComputer.EntityDamageResult> results;
        final long workerStart = System.nanoTime();
        try {
            ++ENTITY_WORKER_BATCHES;
            results = NtWorker.mapBatched(NtThreadPool.getPool("Explosion"), units,
                    snapshot -> ExplosionEntityDamageComputer.computeEntityDamage(
                            snapshot, centerX, centerY, centerZ, dr, worldView),
                    NtWorker.autoBatchSize(units.size()), 5);
        } catch (RuntimeException e) {
            // Workers failed — the units captured on the main thread are still
            // valid, so compute them serially instead of re-running vanilla
            // hurtEntities (which would re-scan the entity sections).
            ++ENTITY_FALLBACKS;
            ExplosionStats.recordEntitySerialFallback();
            LOGGER.error("Explosion entity workers failed; computing damage serially", e);
            results = new ArrayList<>(units.size());
            for (int i = 0; i < units.size(); i++) {
                results.add(ExplosionEntityDamageComputer.computeEntityDamage(
                        units.get(i), centerX, centerY, centerZ, dr, worldView));
            }
        }
        final long workerNanos = System.nanoTime() - workerStart;
        final long applyStart = System.nanoTime();
        applyCaptureResults(results, refs, refCount, unitOfVictim, unitIsGroup, ctx);
        final long applyNanos = System.nanoTime() - applyStart;
        ExplosionStats.recordEntityStage(captureNanos, workerNanos, applyNanos,
                refCount, captured.liveVictims(), captured.dedupHits(),
                units.size(), ENTITY_LAST_GROUPED_VICTIMS);

        logEntityPathCounters();
        return true;
    }

    /** Applies one blast's results in vanilla order: victims are visited in
     *  capture order and each still receives its own push; grouped victims only
     *  share the computed values, not the world writes. */
    private static void applyCaptureResults(
            List<ExplosionEntityDamageComputer.EntityDamageResult> results,
            Entity[] refs, int refCount, int[] unitOfVictim, boolean[] unitIsGroup,
            ExplosionContext ctx) {
        long groupedVictims = 0;
        for (int i = 0; i < refCount; i++) {
            int unit = unitOfVictim[i];
            ExplosionEntityDamageComputer.EntityDamageResult r = results.get(unit);
            if (r == null) continue;
            Entity entity = refs[i];
            if (unitIsGroup[unit]) {
                applyGroupedDamage(r, entity);
                groupedVictims++;
            } else {
                applyEntityDamage(r, entity, ctx);
            }
        }
        ENTITY_LAST_GROUPED_VICTIMS = groupedVictims;
    }

    /** Grouped victim apply: for an exact-class PrimedTnt victim the damage,
     *  bookkeep and onExplosionHit steps are all proven vanilla no-ops (final
     *  hurtServer -> false, non-projectile/non-player, Entity default empty
     *  onExplosionHit), so only the ordered push remains. push(double,double,
     *  double) is the same call vanilla reaches through pushFromExplosion(Vec3)
     *  without allocating the intermediate Vec3. */
    private static void applyGroupedDamage(
            ExplosionEntityDamageComputer.EntityDamageResult result, Entity entity) {
        entity.push(result.kbX(), result.kbY(), result.kbZ());
    }

    /** Captured compute units plus the per-victim unit mapping and the
     *  capture-time entity references. Applying by reference (not by ID
     *  re-lookup) matches vanilla hurtEntities, which iterates its collected
     *  list and still damages an entity that an earlier hit in the same blast
     *  removed. */
    private record CapturedDamage(
            List<ExplosionEntityDamageComputer.EntityDamageSnapshot> units,
            Entity[] refs,
            int refCount,
            int[] unitOfVictim,
            boolean[] unitIsGroup,
            int liveVictims,
            int dedupHits) {}

    private static CapturedDamage captureEntityDamageSnapshots(
            ExplosionContext ctx, WorldReadViewImpl worldView,
            int x0, int y0, int z0, int x1, int y1, int z1, float doubleRadius) {
        AABB box = new AABB(x0, y0, z0, x1, y1, z1);
        double radiusSquare = (double) doubleRadius * doubleRadius;
        ServerExplosion self = ctx.self();
        final boolean isDefaultCalc = ctx.damageCalculator().getClass() == ExplosionDamageCalculator.class;
        // Tier A/B calculators only (the mixin gates the pipeline on
        // ExplosionEligibility). EntityBasedExplosionDamageCalculator
        // inherits every entity hook from the vanilla default implementation, so
        // both tiers have pure, side-effect-free entity damage hooks.
        final boolean pureEntityCalculator = isDefaultCalc
                || ctx.damageCalculator().getClass() == EntityBasedExplosionDamageCalculator.class;
        exposureDedupSize = 0;
        groupSize = 0;
        // [0] = victims routed to the live vanilla exposure path,
        // [1] = exposure samples reused through the context-keyed dedup slot.
        final int[] captureStats = new int[2];

        // The flat view resolves collision shapes with the empty collision context,
        // which is vanilla-exact for every entity that cannot observe a
        // context-dependent shape. In 26.3 exactly three vanilla block categories
        // read the querying entity: scaffolding and powder snow (any entity), and
        // liquids (only a LivingEntity that can stand on the fluid — vanilla only
        // Strider). The fill classified those into flags, so the routing below is
        // per entity instead of per blast: a lava blast still sends TNT/items/
        // arrows/minecarts through the worker flat-view path (their liquid shape is
        // context-free by construction), while LivingEntity exposures stay on the
        // live vanilla path. Flags are computed inline during the fill (O(1) here)
        // instead of scanning the whole view per explosion (1.4% of tick at 348ms).
        final ExplosionFlatViewHelper.EntityContextFlags entityContextFlags = worldView.entityContextFlags();

        List<ExplosionEntityDamageComputer.EntityDamageSnapshot> units = CAPTURE_UNITS;
        units.clear();
        CAPTURE_REF_COUNT = 0;
        PRIMED_TNT_POWDER_KNOWN = false;
        final double centerX = ctx.center().x;
        final double centerY = ctx.center().y;
        final double centerZ = ctx.center().z;
        final boolean stateDependent = entityContextFlags.stateDependent();
        final boolean hasPowderSnow = entityContextFlags.hasPowderSnow();
        // Single pass over the entity sections: the predicate runs in place of
        // vanilla's NO_SPECTATORS selector, so the spatial query and the
        // snapshot capture share one traversal (vanilla's getEntities box →
        // spectator → hurtEntities ignoreExplosion → distance order is
        // preserved, all on the same entities). The predicate always returns
        // false, so no intermediate candidate list is materialised.
        ctx.level().getEntities(ctx.source(), box, entity -> {
            // Exact vanilla PrimedTnt inherits both vanilla filters unchanged:
            // Entity#isSpectator returns false and Entity#ignoreExplosion returns
            // false. Skipping the two virtual calls for that exact class is
            // vanilla-equivalent (subclasses keep the calls) and the 93k-TNT pile
            // runs this predicate for every entity of every blast.
            final boolean exactPrimedTnt = entity.getClass() == PrimedTnt.class;
            if (!exactPrimedTnt) {
                if (entity.isSpectator()) return false;
                if (entity.ignoreExplosion(self)) return false;
            }
            double feetX = entity.getX(), feetY = entity.getY(), feetZ = entity.getZ();
            // Inlined distanceToSqr with the hoisted center: one less virtual call
            // and record accessor per candidate, same arithmetic as vanilla.
            double dx = feetX - centerX;
            double dy = feetY - centerY;
            double dz = feetZ - centerZ;
            if (dx * dx + dy * dy + dz * dz > radiusSquare) return false;
            // Vanilla's chunk promotions land after hurtEntities, so a chunk this blast just
            // loaded is still HIDDEN for vanilla's capture. Skip entities that sat in such
            // chunks before the blast touched the world (ExplosionVisibilitySnapshot).
            // Ordered after the radius filter: this is our own pure filter, so the
            // result set is unchanged while far candidates skip the chunk lookup.
            if (ExplosionVisibilitySnapshot.hiddenAtCapture(entity)) return false;

            int refIndex = CAPTURE_REF_COUNT;
            if (refIndex == CAPTURE_REFS.length) {
                CAPTURE_REFS = java.util.Arrays.copyOf(CAPTURE_REFS, refIndex << 1);
            }
            CAPTURE_REFS[refIndex] = entity;
            CAPTURE_REF_COUNT = refIndex + 1;
            int victimIndex = refIndex;
            ensureVictimCapacity(CAPTURE_REF_COUNT);
            AABB bb = entity.getBoundingBox();
            final boolean isPrimedTnt = exactPrimedTnt || entity instanceof PrimedTnt;
            // PrimedTnt#hurtServer is final and returns false without side effects,
            // and the eligible calculators' entity hooks are inherited pure
            // implementations, so the whole damage path is a no-op for a primed-TNT
            // victim under the same source vanilla uses (the TNT itself, or null).
            // Skipping it is therefore vanilla-observable-equivalent; the knockback
            // remains live and observable.
            final boolean damageElided = isPrimedTnt && pureEntityCalculator
                    && (ctx.source() == null || ctx.source().getClass() == PrimedTnt.class);
            boolean shouldDamage;
            float knockbackMultiplier;
            float exposure = 0.0F;
            boolean exposurePreset = false;
            boolean liveExposure = stateDependent;
            if (!liveExposure) {
                // Short-circuit ordering matters: the powder-snow predicate (a type
                // tag / equipment read) must not run for entities the blast-wide
                // fallback already covers, nor in the common context-free case.
                liveExposure = entityContextFlags.requiresLiveExposure(
                        entity instanceof LivingEntity,
                        hasPowderSnow && powderSnowMayChangeShape(entity));
            }
            if (liveExposure) {
                captureStats[0]++;
                if (damageElided) {
                    shouldDamage = false;
                    knockbackMultiplier = 1.0F;
                } else {
                    shouldDamage = isDefaultCalc
                            ? true : ctx.damageCalculator().shouldDamageEntity(self, entity);
                    knockbackMultiplier = isDefaultCalc
                            ? 1.0F : ctx.damageCalculator().getKnockbackMultiplier(entity);
                }
                // Vanilla hurtEntities samples exposure only when it can affect
                // damage or knockback; with both inert the value is never read.
                // The flat-view worker path applies the same condition.
                if (shouldDamage || knockbackMultiplier != 0.0F) {
                    if (exposureContextIsPositionOnly(entity, hasPowderSnow)) {
                        boolean descending = entity.isDescending();
                        int slot = exposureDedupLookup(bb, feetY, descending);
                        if (slot >= 0) {
                            captureStats[1]++;
                            exposure = EXPOSURE_DEDUP_VALUE[slot];
                        } else {
                            exposure = computeContextAwareExposure(entity, centerX, centerY, centerZ);
                            exposureDedupStore(bb, feetY, descending, exposure);
                        }
                    } else {
                        exposure = computeContextAwareExposure(entity, centerX, centerY, centerZ);
                    }
                    exposurePreset = true;
                }
            } else if (isDefaultCalc) {
                shouldDamage = true;
                knockbackMultiplier = 1.0F;
            } else if (damageElided) {
                shouldDamage = false;
                knockbackMultiplier = 1.0F;
            } else {
                shouldDamage = ctx.damageCalculator().shouldDamageEntity(self, entity);
                knockbackMultiplier = ctx.damageCalculator().getKnockbackMultiplier(entity);
            }
            double eyeY = isPrimedTnt ? feetY : feetY + entity.getEyeHeight();
            // Captured on the main thread so the worker applies it in the exact
            // vanilla product order (1-dist)*exposure*kbMult*(1-res).
            double kbRes = entity instanceof LivingEntity living
                    ? living.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE)
                    : 0.0;

            // Grouping condition: exact vanilla PrimedTnt victim whose damage is
            // elided, on the live-exposure path with a preset sample. Then the
            // computed result only depends on (feet position, eye height, exposure)
            // and the apply step only needs the ordered push - every other
            // per-entity callback is a proven no-op. Subclasses stay own units.
            final boolean groupable = damageElided
                    && liveExposure
                    && exposurePreset
                    && exactPrimedTnt;
            int unit;
            if (groupable) {
                int group = groupLookup(feetX, feetY, feetZ, eyeY, exposure);
                if (group >= 0) {
                    VICTIM_UNITS[victimIndex] = GROUP_UNIT[group];
                    return false;
                }
                boolean asGroup = groupSize < GROUP_CAPACITY;
                unit = addComputeUnit(new ExplosionEntityDamageComputer.EntityDamageSnapshot(entity.getId(),
                        feetX, feetY, feetZ, eyeY,
                        bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ,
                        shouldDamage, knockbackMultiplier, exposure, exposurePreset,
                        kbRes), asGroup);
                if (asGroup) {
                    groupStore(feetX, feetY, feetZ, eyeY, exposure, unit);
                }
            } else {
                unit = addComputeUnit(new ExplosionEntityDamageComputer.EntityDamageSnapshot(entity.getId(),
                        feetX, feetY, feetZ, eyeY,
                        bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ,
                        shouldDamage, knockbackMultiplier, exposure, exposurePreset,
                        kbRes), false);
            }
            VICTIM_UNITS[victimIndex] = unit;
            return false;
        });
        return new CapturedDamage(units, CAPTURE_REFS, CAPTURE_REF_COUNT,
                VICTIM_UNITS, UNIT_IS_GROUP, captureStats[0], captureStats[1]);
    }

    private static void ensureVictimCapacity(int required) {
        if (required <= VICTIM_UNITS.length) return;
        int capacity = Math.max(required, VICTIM_UNITS.length * 2);
        VICTIM_UNITS = java.util.Arrays.copyOf(VICTIM_UNITS, capacity);
    }

    private static int addComputeUnit(
            ExplosionEntityDamageComputer.EntityDamageSnapshot unit, boolean grouped) {
        int index = CAPTURE_UNITS.size();
        CAPTURE_UNITS.add(unit);
        if (index >= UNIT_IS_GROUP.length) {
            UNIT_IS_GROUP = java.util.Arrays.copyOf(UNIT_IS_GROUP, UNIT_IS_GROUP.length * 2);
        }
        UNIT_IS_GROUP[index] = grouped;
        return index;
    }

    /** Finds the compute unit shared by victims with the same captured values;
     *  see the grouping condition in {@code captureEntityDamageSnapshots}. */
    static int groupLookup(double feetX, double feetY, double feetZ, double eyeY, float exposure) {
        for (int i = 0; i < groupSize; i++) {
            if (GROUP_FEET_X[i] == feetX && GROUP_FEET_Y[i] == feetY && GROUP_FEET_Z[i] == feetZ
                    && GROUP_EYE_Y[i] == eyeY && GROUP_EXPOSURE[i] == exposure) {
                return i;
            }
        }
        return -1;
    }

    static void groupStore(double feetX, double feetY, double feetZ, double eyeY,
                           float exposure, int unit) {
        if (groupSize >= GROUP_CAPACITY) return;
        int i = groupSize++;
        GROUP_FEET_X[i] = feetX;
        GROUP_FEET_Y[i] = feetY;
        GROUP_FEET_Z[i] = feetZ;
        GROUP_EYE_Y[i] = eyeY;
        GROUP_EXPOSURE[i] = exposure;
        GROUP_UNIT[i] = unit;
    }

    /** Test hook: compute unit recorded for a group slot. */
    static int groupUnit(int slot) {
        return GROUP_UNIT[slot];
    }

    /** Test hook: number of group slots used by the current/last capture. */
    static int groupCount() {
        return groupSize;
    }

    /**
     * True when vanilla's {@code getSeenPercent} exposure of this entity depends
     * only on its AABB, feet Y and shift-down flag - so two such entities at the
     * same spot always sample bit-identical exposure and can share one call.
     *
     * <p>For a non-living entity the collision context can only feed three vanilla
     * shape readers: liquids (need a {@code LivingEntity}), powder snow (needs
     * {@code fallDistance > 2.5}, a {@code FallingBlockEntity}, or the
     * walk-on-powder predicate) and scaffolding ({@code isAbove} on the entity
     * bottom + {@code isDescending}). {@code MinecartCollisionContext} overrides
     * {@code isAbove} for rail shapes, so minecarts stay conservative.</p>
     *
     * <p>{@code hasPowderSnow} comes from the flat-view fill and is already the
     * blast-wide routing flag used by {@link ExplosionFlatViewHelper.EntityContextFlags}.
     * When it is false the view contains no powder-snow block, so the walk-on-powder
     * predicate cannot affect any sampled shape and the entity-type tag lookup is
     * skipped (it was 18% of capture self-time on the 93k-TNT pile).</p>
     */
    private static boolean exposureContextIsPositionOnly(Entity entity, boolean hasPowderSnow) {
        if (entity.getClass() == PrimedTnt.class) {
            // Exact PrimedTnt cannot be living/falling-block/minecart, so only its
            // fall distance and the powder-snow type tag can matter. With no powder
            // snow in the view the tag is irrelevant; with powder snow it is the
            // same for every exact PrimedTnt of the blast (memoised per capture).
            if (entity.fallDistance > 2.5) return false;
            if (!hasPowderSnow) return true;
            if (!PRIMED_TNT_POWDER_KNOWN) {
                PRIMED_TNT_POWDER_WALKABLE = PowderSnowBlock.canEntityWalkOnPowderSnow(entity);
                PRIMED_TNT_POWDER_KNOWN = true;
            }
            return !PRIMED_TNT_POWDER_WALKABLE;
        }
        if (!hasPowderSnow) {
            return !(entity instanceof LivingEntity)
                    && !(entity instanceof FallingBlockEntity)
                    && !(entity instanceof AbstractMinecart)
                    && !(entity.fallDistance > 2.5);
        }
        return !(entity instanceof LivingEntity)
                && !(entity instanceof FallingBlockEntity)
                && !(entity instanceof AbstractMinecart)
                && !(entity.fallDistance > 2.5)
                && !PowderSnowBlock.canEntityWalkOnPowderSnow(entity);
    }

    /** Returns the slot of an existing dedup group for this exact sample context,
     *  or -1 when this entity must sample exposure itself. */
    static int exposureDedupLookup(AABB bb, double feetY, boolean descending) {
        for (int i = 0; i < exposureDedupSize; i++) {
            if (EXPOSURE_DEDUP_FEET_Y[i] == feetY
                    && EXPOSURE_DEDUP_DESCENDING[i] == descending
                    && EXPOSURE_DEDUP_AABB[i * 6] == bb.minX
                    && EXPOSURE_DEDUP_AABB[i * 6 + 1] == bb.minY
                    && EXPOSURE_DEDUP_AABB[i * 6 + 2] == bb.minZ
                    && EXPOSURE_DEDUP_AABB[i * 6 + 3] == bb.maxX
                    && EXPOSURE_DEDUP_AABB[i * 6 + 4] == bb.maxY
                    && EXPOSURE_DEDUP_AABB[i * 6 + 5] == bb.maxZ) {
                return i;
            }
        }
        return -1;
    }

    /** Records one sampled exposure group; silently stops growing at the cap so a
     *  blast full of distinct non-living entities cannot unbound the scratch. */
    static void exposureDedupStore(AABB bb, double feetY, boolean descending, float exposure) {
        if (exposureDedupSize >= EXPOSURE_DEDUP_CAPACITY) return;
        int i = exposureDedupSize++;
        EXPOSURE_DEDUP_FEET_Y[i] = feetY;
        EXPOSURE_DEDUP_DESCENDING[i] = descending;
        EXPOSURE_DEDUP_AABB[i * 6] = bb.minX;
        EXPOSURE_DEDUP_AABB[i * 6 + 1] = bb.minY;
        EXPOSURE_DEDUP_AABB[i * 6 + 2] = bb.minZ;
        EXPOSURE_DEDUP_AABB[i * 6 + 3] = bb.maxX;
        EXPOSURE_DEDUP_AABB[i * 6 + 4] = bb.maxY;
        EXPOSURE_DEDUP_AABB[i * 6 + 5] = bb.maxZ;
        EXPOSURE_DEDUP_VALUE[i] = exposure;
    }

    private static void logEntityPathCounters() {
        long paths = ++WORKER_ENTITY_PATHS;
        if ((paths & (paths - 1)) == 0) {
            LOGGER.info("Explosion entity paths: active={}, workerBatches={}, fallbacks={}, units={}, groupedVictims={}",
                    paths, ENTITY_WORKER_BATCHES, ENTITY_FALLBACKS, ENTITY_LAST_UNITS, ENTITY_LAST_GROUPED_VICTIMS);
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

    /**
     * Drops the cross-blast static state that can reference a world: the last capture's
     * entity references and the apply-target context/entity. Called when the owning server
     * stops, so a client that leaves a single-player world does not retain that world's
     * {@code ServerLevel} and entities through these statics.
     *
     * <p>{@link #CAPTURE_UNITS} is left alone: it holds primitive-only records and the
     * next capture clears it anyway.</p>
     */
    public static void clearStaticState() {
        java.util.Arrays.fill(CAPTURE_REFS, 0, CAPTURE_REF_COUNT, null);
        CAPTURE_REF_COUNT = 0;
        applyCtx = null;
        applyEntity = null;
        clearExposureDedup();
        groupSize = 0;
    }

    /** Test/cleanup hook: drops the per-blast exposure dedup scratch. */
    static void clearExposureDedup() {
        exposureDedupSize = 0;
    }

    /** Test hook: sampled exposure of a stored dedup group. */
    static float exposureDedupValue(int slot) {
        return EXPOSURE_DEDUP_VALUE[slot];
    }

    // ── Main-thread entity helpers ───────────────────────────────────────────

    /**
     * True when a powder-snow block in the captured view could return a non-empty
     * collision shape for this entity's own {@code CollisionContext}, which means
     * the live per-entity {@code getSeenPercent} is required
     * ({@link ExplosionFlatViewHelper.EntityContextFlags}).
     *
     * <p>26.3's {@code PowderSnowBlock#getCollisionShape} returns
     * {@code Shapes.empty()} unless one of exactly three conditions holds: the
     * entity's fall distance is above 2.5, the entity is a
     * {@code FallingBlockEntity}, or the entity can walk on powder snow
     * ({@code EntityTypeTags.POWDER_SNOW_WALKABLE_MOBS} / leather boots). When all
     * three are false the live shape equals the flat view's empty-context shape for
     * every powder-snow block regardless of entity position, so the flat exposure is
     * bit-identical to vanilla and sampling it live would only add main-thread
     * work.</p>
     *
     * <p>Called on the main thread during capture, only when the view actually
     * contains powder snow ({@code hasPowderSnow} short-circuits the call).</p>
     */
    private static boolean powderSnowMayChangeShape(Entity entity) {
        if (entity.fallDistance > 2.5) {
            return true;
        }
        if (entity instanceof FallingBlockEntity) {
            return true;
        }
        return PowderSnowBlock.canEntityWalkOnPowderSnow(entity);
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
