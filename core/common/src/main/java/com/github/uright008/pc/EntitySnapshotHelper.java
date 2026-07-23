package com.github.uright008.pc;

import com.github.uright008.pc.simd.SimdBatchOps;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.*;

/**
 * Pre-collects entity references on the main thread for safe consumption
 * by worker threads during parallel block-entity ticking.
 *
 * <p>Motivation: {@code Level.getEntities()}/{@code getEntitiesOfClass()}
 * iterate {@code EntitySectionStorage} which is not designed for concurrent
 * access.  Instead of making entity storage thread-safe, we snapshot the
 * references the parallel phase needs.</p>
 *
 * <p>Usage in a mixin:</p>
 * <pre>
 *   // Main thread — before parallel phase
 *   List&lt;BlockPos&gt; positions = hoppers.stream().map(HopperBlockEntity::getBlockPos).toList();
 *   Map&lt;BlockPos, List&lt;ItemEntitySnapshot&gt;&gt; itemSnapshot =
 *       EntitySnapshotHelper.collectHopperItemEntities(level, positions);
 *
 *   for (var hopper : batch) {
 *       futures.add(CompletableFuture.runAsync(() -> {
 *           List&lt;ItemEntitySnapshot&gt; items = itemSnapshot.get(hopper.getBlockPos());
 *           // ... use items safely without Level access
 *       }, pool));
 *   }
 * </pre>
 */
public final class EntitySnapshotHelper {

    private EntitySnapshotHelper() {}

    /**
     * For each hopper position, pre-collect ItemEntity instances that the hopper
     * would see via {@code HopperBlockEntity.getItemsAtAndAbove()}.
     * <p>
     * The returned map is a plain {@link HashMap} — it is only read (never written)
     * by worker threads, so concurrent reads are safe.
     *
     * @param level          the level (must be called from main thread)
     * @param hopperPositions positions of hoppers to collect entities for
     * @return unmodifiable map from hopper position → list of visible ItemEntities
     */
    public static Map<BlockPos, List<ItemEntitySnapshot>> collectHopperItemEntities(
            Level level, Collection<BlockPos> hopperPositions) {

        if (hopperPositions.isEmpty()) return Collections.emptyMap();

        // Pre-initialise empty lists for every hopper
        Map<BlockPos, List<ItemEntitySnapshot>> result = new HashMap<>(hopperPositions.size());
        for (BlockPos pos : hopperPositions) {
            result.put(pos.immutable(), new ArrayList<>());
        }

        // BlockPos → owning hopper: an entity at this block is within SUCK_AABB
        // of exactly one hopper (SUCK_AABB = hopper's own block + block above).
        // ItemEntity bounding box is 0.25³ — it never spans two X/Z columns.
        Map<BlockPos, BlockPos> blockToHopper = new HashMap<>(hopperPositions.size() * 2);
        for (BlockPos hp : hopperPositions) {
            BlockPos imm = hp.immutable();
            blockToHopper.put(imm.above(), imm);
            blockToHopper.put(imm, imm);
        }

        // Single bounding-box enclosing every hopper's SUCK_AABB
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : hopperPositions) {
            int x = pos.getX(), y = pos.getY(), z = pos.getZ();
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
        }
        // SUCK_AABB extends from (x, y, z) to (x+1, y+1.125, z+1)

        if (SimdBatchOps.simdEnabled()) {
            int[] hits = new int[SimdBatchOps.slotCount()];
            int nHits = SimdBatchOps.intersectAABB(hits, minX, minY, minZ,
                    maxX + 1, maxY + 1.125, maxZ + 1);
            for (int i = 0; i < nHits; i++) {
                int entityId = SimdBatchOps.slotToEntityId(hits[i]);
                if (entityId < 0) continue;
                net.minecraft.world.entity.Entity e = level.getEntity(entityId);
                if (!(e instanceof ItemEntity item) || !item.isAlive()) continue;
                BlockPos hopperPos = blockToHopper.get(item.blockPosition());
                if (hopperPos == null) continue;
                AABB suckAabb = HopperAabb.SUCK_AABB.move(
                        hopperPos.getX(), hopperPos.getY(), hopperPos.getZ());
                if (item.getBoundingBox().intersects(suckAabb)) {
                    result.get(hopperPos)
                            .add(new ItemEntitySnapshot(item.getItem().copy(), item.blockPosition()));
                }
            }
        } else {
            AABB union = new AABB(minX, minY, minZ, maxX + 1, maxY + 1.125, maxZ + 1);
            List<ItemEntity> allItems = level.getEntitiesOfClass(
                    ItemEntity.class, union, EntitySelector.ENTITY_STILL_ALIVE);

            for (ItemEntity item : allItems) {
                BlockPos hopperPos = blockToHopper.get(item.blockPosition());
                if (hopperPos == null) continue;
                AABB suckAabb = HopperAabb.SUCK_AABB.move(
                        hopperPos.getX(), hopperPos.getY(), hopperPos.getZ());
                if (item.getBoundingBox().intersects(suckAabb)) {
                    result.get(hopperPos)
                            .add(new ItemEntitySnapshot(item.getItem().copy(), item.blockPosition()));
                }
            }
        }

        // Seal lists for thread-safe read-only access
        result.replaceAll((k, v) -> List.copyOf(v));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Pre-collect ItemEntity snapshots for an AABB around a center position.
     * Useful for MinecartHopper or other non-block hoppers.
     *
     * @return unmodifiable list of ItemEntitySnapshots visible from the given AABB
     */
    public static List<ItemEntitySnapshot> collectItemEntities(Level level, AABB aabb) {
        return List.copyOf(level.getEntitiesOfClass(
                ItemEntity.class, aabb, EntitySelector.ENTITY_STILL_ALIVE)
                .stream()
                .map(e -> new ItemEntitySnapshot(e.getItem().copy(), e.blockPosition()))
                .toList());
    }

    /**
     * The vanilla {@code Hopper.SUCK_AABB} shape — exposed here so
     * {@code EntitySnapshotHelper} can replicate the vanilla query
     * without depending on the hopper module directly.
     */
    static final class HopperAabb {
        static final AABB SUCK_AABB = net.minecraft.world.level.block.entity.Hopper.SUCK_AABB;
    }
}
