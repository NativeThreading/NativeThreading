package com.github.uright008.hp;

import com.github.uright008.hp.mixin.HopperBlockEntityAccessor;
import com.github.uright008.pc.EntitySnapshotHelper;
import com.github.uright008.pc.ItemEntitySnapshot;
import com.github.uright008.pc.ParallelThreadPool;
import com.github.uright008.pc.ParallelWorker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;


import java.util.*;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Two-phase parallel hopper transfer engine.
 *
 * <h3>Problem</h3>
 * Vanilla {@code HopperBlockEntity.pushItemsTick()} directly mutates source
 * and target containers, creating data races when multiple hoppers tick
 * concurrently.  Traditional locking / chunk-colouring adds complexity.
 *
 * <h3>Solution: Incremental plan → sequential apply</h3>
 * <ol>
 *   <li><b>Phase 1 (main-thread capture + parallel selection)</b> — the main
 *       thread evaluates hopper and container state into prevalidated
 *       snapshots. Workers select a {@link HopperTransferPlan} without
 *       accessing world or container objects.</li>
 *   <li><b>Phase 2 (sequential)</b> — plans are executed in a deterministic
 *       order.  If a plan is no longer valid (target became full, item was
 *       already taken by an earlier plan, etc.), it is simply skipped.</li>
 * </ol>
 *
 * <p>Because each hopper moves at most one item per tick and hoppers only
 * interact pairwise (A→B or A←B), the sequential execution naturally
 * resolves conflicts: later plans see the updated state from earlier plans
 * and skip themselves if preconditions fail.</p>
 */
public final class HopperParallelHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger("hopper");

    private HopperParallelHelper() {}

    // ── Public entry point ──────────────────────

    /**
     * Process all hopper block entities in this tick using the two-phase
     * parallel approach.
     *
     * @param level   the level
     * @param hoppers list of {@link HopperBlockEntity} to tick
     */
    public static void processHoppers(Level level, List<HopperBlockEntity> hoppers) {
        if (hoppers.isEmpty()) return;

        List<BlockPos> positions = hoppers.stream()
                .map(HopperBlockEntity::getBlockPos)
                .toList();
        Map<BlockPos, List<ItemEntitySnapshot>> itemSnapshots =
                EntitySnapshotHelper.collectHopperItemEntities(level, positions);

        List<HopperPlanningSnapshot> snapshots = captureEach(
                hoppers,
                hopper -> captureSnapshot(level, hopper, itemSnapshots));

        ParallelWorker.Batch<HopperPlanningSnapshot, PlanResult> batch =
                new ParallelWorker.Batch<>(ParallelThreadPool.getPool("Hopper"));
        for (HopperPlanningSnapshot snapshot : snapshots) batch.add(snapshot);
        List<PlanResult> results = batch.flush(HopperParallelHelper::selectPlan, 30);

        executePlans(level, results);
    }

    static <T, R> List<R> captureEach(List<T> entries, java.util.function.Function<T, R> capture) {
        List<R> snapshots = new ArrayList<>(entries.size());
        for (T entry : entries) {
            try {
                snapshots.add(capture.apply(entry));
            } catch (Throwable t) {
                LOGGER.warn("Hopper snapshot capture failed", t);
            }
        }
        return snapshots;
    }

    // ── Main-thread capture + pure worker selection ─────────────

    /**
     * Evaluates all live hopper, world, and container state on the main thread.
     * The returned snapshot contains only prevalidated transfer candidates.
     */
    private static HopperPlanningSnapshot captureSnapshot(
            Level level,
            HopperBlockEntity hopper,
            Map<BlockPos, List<ItemEntitySnapshot>> itemSnapshots) {

        HopperBlockEntityAccessor acc = (HopperBlockEntityAccessor) hopper;

        // Snapshot cooldown — decrement happens in Phase 2 on main thread
        int cooldown = acc.getCooldownTime();
        BlockPos pos = hopper.getBlockPos();
        if (cooldown > 1) {
            return new HopperPlanningSnapshot(pos, cooldown, null, null);
        }

        // Enabled check
        BlockState state = level.getBlockState(pos);
        if (!state.getValue(HopperBlock.ENABLED)) {
            return new HopperPlanningSnapshot(pos, 0, null, null);
        }

        // ── Try PUSH: eject one item to facing container ──
        Direction facing = acc.getFacing();
        BlockPos targetPos = pos.relative(facing);
        Container target = getContainerSafe(level, targetPos);
        @Nullable HopperTransferPlan pushPlan = null;

        if (target != null && !hopper.isEmpty()) {
            for (int slot = 0; slot < hopper.getContainerSize(); slot++) {
                ItemStack stack = hopper.getItem(slot);
                if (!stack.isEmpty()) {
                    if (canAcceptItem(target, stack, facing.getOpposite())) {
                        pushPlan = HopperTransferPlan.push(pos, slot, stack, targetPos, facing);
                        break;
                    }
                }
            }
        }
        if (pushPlan != null) return new HopperPlanningSnapshot(pos, 0, pushPlan, null);

        // ── Try PULL: suck one item from above ──
        if (acc.invokeInventoryFull()) return new HopperPlanningSnapshot(pos, 0, pushPlan, null);

        // First try: container above
        BlockPos abovePos = pos.above();
        Container source = getContainerSafe(level, abovePos);
        if (source != null) {
            for (int slot : getSlots(source, Direction.DOWN)) {
                ItemStack stack = source.getItem(slot);
                if (!stack.isEmpty()
                        && canTakeFromContainer(hopper, source, stack, slot)) {
                    return new HopperPlanningSnapshot(pos, 0, pushPlan,
                            HopperTransferPlan.pullFromContainer(pos, abovePos, slot, stack));
                }
            }
            // Container exists above → skip ItemEntities (vanilla behaviour)
            return new HopperPlanningSnapshot(pos, 0, pushPlan, null);
        }

        // No container above → try ItemEntities
        BlockState aboveState = level.getBlockState(abovePos);
        boolean blocked = aboveState.isCollisionShapeFullBlock(level, abovePos)
                && !aboveState.is(BlockTags.DOES_NOT_BLOCK_HOPPERS);

        if (!blocked) {
            List<ItemEntitySnapshot> items = itemSnapshots.getOrDefault(pos, List.of());
            if (!items.isEmpty()) {
                ItemEntitySnapshot snapshot = items.getFirst();
                ItemStack entityStack = snapshot.item();
                if (!entityStack.isEmpty() && hopper.canPlaceItem(0, entityStack)) {
                    return new HopperPlanningSnapshot(pos, 0, pushPlan,
                            HopperTransferPlan.pullEntity(pos, snapshot.pos(), entityStack));
                }
            }
        }

        return new HopperPlanningSnapshot(pos, 0, pushPlan, null);
    }

    /**
     * Runs in workers using only a prevalidated immutable snapshot. It makes
     * no calls into the level, block entities, containers, or callbacks.
     */
    static PlanResult selectPlan(HopperPlanningSnapshot snapshot) {
        HopperTransferPlan plan = snapshot.cooldownSnapshot > 1
                ? null
                : snapshot.pushPlan != null ? snapshot.pushPlan : snapshot.pullPlan;
        return new PlanResult(plan, snapshot.hopperPos, snapshot.cooldownSnapshot);
    }

    // ── Phase 2: sequential plan execution ──────

    /**
     * Execute plans sequentially on the main thread.
     * Each plan is validated against current world state before executing.
     */
    private static void executePlans(
            Level level,
            List<PlanResult> results) {

        // ── Apply cooldown decrement + tickedGameTime on main thread ──
        long gameTime = level.getGameTime();
        // Apply cooldown decrement + tickedGameTime to EVERY hopper
        // (vanilla pushItemsTick does this unconditionally before checking cooldown)
        for (PlanResult r : results) {
            Container h = getContainerSafe(level, r.hopperPos);
            if (h instanceof HopperBlockEntityAccessor acc) {
                int newCooldown = Math.max(0, r.cooldownSnapshot - 1);
                acc.setCooldownTime(newCooldown);
                acc.setTickedGameTime(gameTime);
            }
        }

        // ── Execute plans for hoppers whose cooldown expired ──
        Set<BlockPos> succeededHoppers = new HashSet<>();

        for (PlanResult r : results) {
            HopperTransferPlan plan = r.plan;
            if (plan == null) continue;
            if (succeededHoppers.contains(plan.hopperPos)) continue;

            // Skip if cooldown hasn't expired yet
            Container hopper = getContainerSafe(level, plan.hopperPos);
            if (hopper instanceof HopperBlockEntityAccessor acc) {
                if (acc.getCooldownTime() > 0) continue;
            }

            boolean ok = switch (plan.kind) {
                case PUSH -> {
                    boolean pushed = executePush(level, plan);
                    // Vanilla tries PULL regardless of PUSH success/failure,
                    // as long as the hopper inventory isn't full.
                    boolean pulled = false;
                    Container h2 = getContainerSafe(level, plan.hopperPos);
                    if (h2 != null && !isContainerFull(h2)) {
                        pulled = tryPullInline(level, plan.hopperPos);
                    }
                    yield pushed || pulled;
                }
                case PULL -> executePull(level, plan);
            };

            if (ok) {
                succeededHoppers.add(plan.hopperPos);
            }
        }

        // Only succeeded hoppers get cooldown — same as vanilla:
        // tryMoveItems() sets cooldown=8 on success, leaves it at 0 on failure.
        for (PlanResult r : results) {
            if (r.plan != null && succeededHoppers.contains(r.plan.hopperPos)) {
                Container h = getContainerSafe(level, r.plan.hopperPos);
                if (h instanceof HopperBlockEntityAccessor acc) {
                    acc.setCooldownTime(8);
                    if (h instanceof HopperBlockEntity be) be.setChanged();
                }
            }
        }
    }

    private static boolean executePush(Level level, HopperTransferPlan plan) {
        BlockPos pos = plan.hopperPos;
        Container hopper = getContainerSafe(level, pos);
        Container target = getContainerSafe(level, plan.otherPos);
        if (hopper == null || target == null) return false;

        // Re-validate: does the hopper still have the item?
        ItemStack current = hopper.getItem(plan.hopperSlot);
        if (current.isEmpty()
                || !ItemStack.isSameItemSameComponents(current, plan.snapshot)) {
            return false;
        }

        // Re-validate: can target still accept?
        assert plan.direction != null;
        Direction intoDir = plan.direction.getOpposite();
        if (!canAcceptItem(target, current, intoDir)) {
            return false;
        }

        ItemStack removed = hopper.removeItem(plan.hopperSlot, 1);
        if (removed.isEmpty()) return false;

        ItemStack leftover = HopperBlockEntity.addItem(hopper, target, removed, intoDir);
        if (!leftover.isEmpty()) {
            ItemStack sourceAfter = hopper.getItem(plan.hopperSlot);
            if (sourceAfter.isEmpty()) {
                hopper.setItem(plan.hopperSlot, leftover);
            } else if (ItemStack.isSameItemSameComponents(sourceAfter, leftover)) {
                sourceAfter.grow(leftover.getCount());
            } else {
                hopper.setItem(plan.hopperSlot, leftover);
            }
            return false;
        }

        target.setChanged();
        return true;
    }

    private static boolean executePull(Level level, HopperTransferPlan plan) {
        BlockPos pos = plan.hopperPos;
        Container hopper = getContainerSafe(level, pos);
        if (hopper == null) return false;

        if (isContainerFull(hopper)) return false;

        if (plan.hopperSlot >= 0) {
            // Pull from container
            Container source = getContainerSafe(level, plan.otherPos);
            if (source == null) return false;

            ItemStack current = source.getItem(plan.hopperSlot);
            if (current.isEmpty()
                    || !ItemStack.isSameItemSameComponents(current, plan.snapshot)) {
                return false;
            }

            if (!canTakeFromContainer(hopper, source, current, plan.hopperSlot)) {
                return false;
            }

            ItemStack removed = source.removeItem(plan.hopperSlot, 1);
            if (removed.isEmpty()) return false;

            ItemStack leftover = HopperBlockEntity.addItem(source, hopper, removed, null);
            if (!leftover.isEmpty()) {
                ItemStack sourceAfter = source.getItem(plan.hopperSlot);
                if (sourceAfter.isEmpty()) {
                    source.setItem(plan.hopperSlot, leftover);
                } else if (ItemStack.isSameItemSameComponents(sourceAfter, leftover)) {
                    sourceAfter.grow(leftover.getCount());
                } else {
                    source.setItem(plan.hopperSlot, leftover);
                }
                return false;
            }

            source.setChanged();
            return true;
        } else {
            // Pull from ItemEntity — re-query on main thread
            ItemEntity entity = findItemEntity(level, plan);
            if (entity == null || !entity.isAlive()) return false;

            ItemStack entityStack = entity.getItem();
            if (entityStack.isEmpty()
                    || !ItemStack.isSameItemSameComponents(entityStack, plan.snapshot)) {
                return false;
            }

            return HopperBlockEntity.addItem(hopper, entity);
        }
    }

    /**
     * Inline pull attempt after a successful push, matching vanilla behaviour
     * where {@code tryMoveItems} calls both {@code ejectItems} and
     * {@code suckInItems} in the same tick.  Runs on the main thread.
     */
    private static boolean tryPullInline(Level level, BlockPos hopperPos) {
        Container hopper = getContainerSafe(level, hopperPos);
        if (hopper == null || isContainerFull(hopper)) return false;

        BlockPos abovePos = hopperPos.above();
        Container source = getContainerSafe(level, abovePos);
        if (source != null) {
            for (int slot : getSlots(source, Direction.DOWN)) {
                ItemStack stack = source.getItem(slot);
                if (!stack.isEmpty()
                        && canTakeFromContainer(hopper, source, stack, slot)) {
                    ItemStack removed = source.removeItem(slot, 1);
                    if (!removed.isEmpty()) {
                        ItemStack leftover = HopperBlockEntity.addItem(source, hopper, removed, null);
                        if (leftover.isEmpty()) {
                            return true;
                        }
                        // Rollback on failure
                        ItemStack sourceAfter = source.getItem(slot);
                        if (sourceAfter.isEmpty()) {
                            source.setItem(slot, leftover);
                        } else if (ItemStack.isSameItemSameComponents(sourceAfter, leftover)) {
                            sourceAfter.grow(leftover.getCount());
                        } else {
                            source.setItem(slot, leftover);
                        }
                    }
                }
            }
            // Container exists above → skip ItemEntities (vanilla behaviour)
            return false;
        }

        // No container above → try ItemEntities
        BlockState aboveState = level.getBlockState(abovePos);
        boolean blocked = aboveState.isCollisionShapeFullBlock(level, abovePos)
                && !aboveState.is(BlockTags.DOES_NOT_BLOCK_HOPPERS);
        if (!blocked) {
            List<ItemEntity> items = level.getEntitiesOfClass(
                    ItemEntity.class,
                    Hopper.SUCK_AABB.move(
                            hopperPos.getX(), hopperPos.getY(), hopperPos.getZ()),
                    net.minecraft.world.entity.EntitySelector.ENTITY_STILL_ALIVE);
            for (ItemEntity entity : items) {
                if (entity.isAlive() && !entity.getItem().isEmpty()) {
                    return HopperBlockEntity.addItem(hopper, entity);
                }
            }
        }
        return false;
    }

    // ── Thread-safe container access ────────────

    static Container getContainerSafe(Level level, BlockPos pos) {
        return HopperBlockEntity.getContainerAt(level, pos);
    }

    // ── Validation helpers ──────────────────────

    private static boolean canAcceptItem(Container container, ItemStack stack, Direction side) {
        int[] slots = getSlots(container, side);
        WorldlyContainer wc = container instanceof WorldlyContainer w ? w : null;
        for (int slot : slots) {
            if (!container.canPlaceItem(slot, stack)) continue;
            if (wc != null && !wc.canPlaceItemThroughFace(slot, stack, side)) continue;
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(existing, stack)
                    && existing.getCount() < existing.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    private static boolean canTakeFromContainer(
            Container into, Container from, ItemStack stack, int slot) {
        if (!from.canTakeItem(into, slot, stack)) return false;
        return !(from instanceof WorldlyContainer wc)
                || wc.canTakeItemThroughFace(slot, stack, Direction.DOWN);
    }

    private static boolean isContainerFull(Container container) {
        int[] slots = getSlots(container, null);
        for (int slot : slots) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || stack.getCount() < stack.getMaxStackSize()) {
                return false;
            }
        }
        return true;
    }

    private static int[] getSlots(Container container, Direction side) {
        if (container instanceof WorldlyContainer wc && side != null) {
            return wc.getSlotsForFace(side);
        }
        int size = container.getContainerSize();
        int[] slots = new int[size];
        for (int i = 0; i < size; i++) slots[i] = i;
        return slots;
    }

    private static ItemEntity findItemEntity(Level level, HopperTransferPlan plan) {
        List<ItemEntity> items = level.getEntitiesOfClass(
                ItemEntity.class,
                Hopper.SUCK_AABB.move(
                        plan.hopperPos.getX(),
                        plan.hopperPos.getY(),
                        plan.hopperPos.getZ()),
                net.minecraft.world.entity.EntitySelector.ENTITY_STILL_ALIVE);
        for (ItemEntity e : items) {
            if (e.isAlive()
                    && ItemStack.isSameItemSameComponents(e.getItem(), plan.snapshot)) {
                return e;
            }
        }
        return null;
    }

    // ── Phase 1 result ──────────────────────────

    /**
     * Result of the parallel read-phase: a possible transfer plan plus
     * a snapshot of the cooldown at decision time.  All mutations
     * (cooldown decrement, tickedGameTime, actual transfers) happen in
     * Phase 2 on the main thread to guarantee visibility.
     */
    record PlanResult(@Nullable HopperTransferPlan plan, BlockPos hopperPos, int cooldownSnapshot) {}

    record HopperPlanningSnapshot(
            BlockPos hopperPos,
            int cooldownSnapshot,
            @Nullable HopperTransferPlan pushPlan,
            @Nullable HopperTransferPlan pullPlan) {}
}
