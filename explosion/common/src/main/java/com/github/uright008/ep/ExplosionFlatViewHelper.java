package com.github.uright008.ep;

import com.github.uright008.pc.ChunkGrid;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class ExplosionFlatViewHelper {
    private ExplosionFlatViewHelper() {}

    @FunctionalInterface
    public interface BlockLookup<B> {
        B get(int sectionX, int sectionZ, int blockY, int localX, int localY, int localZ);
    }

    /**
     * Entity-context shape profile of a captured flat view.
     *
     * <p>The flat view resolves collision shapes on the main thread with
     * {@link net.minecraft.world.phys.shapes.CollisionContext#empty()}
     * ({@code state.getCollisionShape(level, pos)}), while vanilla's
     * {@code ServerExplosion.getSeenPercent} clips with
     * {@link net.minecraft.world.phys.shapes.CollisionContext#of(net.minecraft.world.entity.Entity)}.
     * In MC 26.3 exactly three vanilla block classes read the collision context in
     * {@code getCollisionShape}: {@link ScaffoldingBlock} ({@code isAbove} /
     * {@code isDescending}), {@link PowderSnowBlock} (fall distance / boots /
     * {@code isDescending}) and {@link LiquidBlock}. Their presence is captured here
     * so the entity stage can decide, per entity, whether the flat view is
     * vanilla-exact for that entity's exposure or the live per-entity
     * {@code getSeenPercent} must be used.</p>
     *
     * <p>{@link LiquidBlock} is special: its context-dependent shape only exists for
     * a {@code LivingEntity} that satisfies {@code getLiquidCollisionShape()} and
     * {@code canStandOnFluid} ({@link LiquidBlock#getCollisionShape} returns
     * {@link net.minecraft.world.phys.shapes.Shapes#empty()} for every other
     * entity). Non-living entities therefore have a context-free liquid shape, and
     * their flat-view exposure is bit-identical to vanilla even in a lava
     * blast.</p>
     *
     * <p><b>Modded caveat</b> (pre-existing F9 assumption): a modded block that
     * overrides {@code getCollisionShape(..., CollisionContext)} and varies its
     * shape by the querying entity is not detected here. Such a block only makes the
     * flat view inexact if it appears inside the blast volume; the liquid-only
     * narrowing below applies the same assumption that the existing no-context-block
     * path already relies on. A modded {@link LiquidBlock} subclass is treated as
     * state-dependent (conservative), because its shape override is unknown.</p>
     */
    public record EntityContextFlags(boolean stateDependent, boolean hasLiquid) {
        public static final EntityContextFlags NONE = new EntityContextFlags(false, false);

        /** True when this entity's exposure must be computed with the live entity
         *  context (vanilla {@code getSeenPercent}) instead of the flat view. */
        public boolean requiresLiveExposure(boolean livingEntity) {
            return this.stateDependent || (this.hasLiquid && livingEntity);
        }
    }

    public static <B> B[] fill(B[] dst, int minX, int minY, int minZ,
                               int maxX, int maxY, int maxZ,
                               int strideY, int strideZ, BlockLookup<B> lookup) {
        for (int z = minZ; z <= maxZ; z++) {
            int cz = z >> 4;
            int lz = z & 15;
            int zOff = (z - minZ) * strideZ;
            for (int y = minY; y <= maxY; y++) {
                int ly = y & 15;
                int yzOff = zOff + (y - minY) * strideY;
                int cx = minX >> 4;
                int lx = minX & 15;
                int i = yzOff;
                for (int x = minX; x <= maxX; x++, i++) {
                    dst[i] = lookup.get(cx, cz, y, lx, ly, lz);
                    if (++lx == 16) { lx = 0; cx++; }
                }
            }
        }
        return dst;
    }

    /**
     * Fills the flat array from a {@link ChunkGrid} in section-major order.
     * Each section is resolved once; sections that are entirely air (the common
     * case in an explosion volume) are skipped with a bulk AIR fill instead of
     * per-cell palette reads. Result is identical to {@link #fill} with
     * {@code chunkGrid::getBlockState}.
     */
    public static BlockState[] fillSectioned(BlockState[] dst,
                                             int minX, int minY, int minZ,
                                             int maxX, int maxY, int maxZ,
                                             int strideY, int strideZ,
                                             ChunkGrid chunkGrid) {
        fillSectionedWithContext(dst, minX, minY, minZ, maxX, maxY, maxZ, strideY, strideZ, chunkGrid);
        return dst;
    }

    /**
     * Like {@link #fillSectioned} but also classifies the entity-context blocks in
     * the filled view (scaffolding / powder snow / liquid) for the entity stage's
     * per-entity exposure routing. Computed inline during the fill so the caller
     * avoids a second O(N) scan; the per-cell classification short-circuits once
     * both categories have been observed (the common case is neither, so the
     * classification piggybacks on the palette reads that already happen).
     */
    public static EntityContextFlags fillSectionedWithContext(BlockState[] dst,
                                                              int minX, int minY, int minZ,
                                                              int maxX, int maxY, int maxZ,
                                                              int strideY, int strideZ,
                                                              ChunkGrid chunkGrid) {
        BlockState air = Blocks.AIR.defaultBlockState();
        ChunkGrid.SectionRef ref = new ChunkGrid.SectionRef();
        boolean stateDependent = false;
        boolean hasLiquid = false;
        for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
            int zMin = Math.max(minZ, cz << 4);
            int zMax = Math.min(maxZ, (cz << 4) + 15);
            for (int secY = minY >> 4; secY <= maxY >> 4; secY++) {
                int yMin = Math.max(minY, secY << 4);
                int yMax = Math.min(maxY, (secY << 4) + 15);
                for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
                    int xMin = Math.max(minX, cx << 4);
                    int xMax = Math.min(maxX, (cx << 4) + 15);

                    chunkGrid.getSection(cx, cz, secY << 4, ref);
                    LevelChunkSection section = ref.section;
                    if (section == null || section.hasOnlyAir()) {
                        for (int z = zMin; z <= zMax; z++) {
                            int zOff = (z - minZ) * strideZ;
                            for (int y = yMin; y <= yMax; y++) {
                                java.util.Arrays.fill(dst,
                                        zOff + (y - minY) * strideY + (xMin - minX),
                                        zOff + (y - minY) * strideY + (xMax - minX) + 1, air);
                            }
                        }
                    } else {
                        for (int z = zMin; z <= zMax; z++) {
                            int zOff = (z - minZ) * strideZ;
                            int lz = z & 15;
                            for (int y = yMin; y <= yMax; y++) {
                                int i = zOff + (y - minY) * strideY + (xMin - minX);
                                int ly = y & 15;
                                for (int x = xMin; x <= xMax; x++, i++) {
                                    BlockState state = section.getBlockState(x & 15, ly, lz);
                                    dst[i] = state;
                                    if (!stateDependent || !hasLiquid) {
                                        Block block = state.getBlock();
                                        if (!stateDependent && isStateDependentContextBlock(block)) {
                                            stateDependent = true;
                                        }
                                        if (!hasLiquid && isPlainLiquidBlock(block)) {
                                            hasLiquid = true;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return new EntityContextFlags(stateDependent, hasLiquid);
    }

    /** Scaffolding and powder snow vary their collision shape by the querying
     *  entity for any entity type, so a view containing either must use the live
     *  per-entity exposure for everyone. A modded {@link LiquidBlock} subclass is
     *  grouped here too: its shape override is unknown, so treat it as if it could
     *  vary by context. */
    static boolean isStateDependentContextBlock(Block block) {
        if (block instanceof ScaffoldingBlock || block instanceof PowderSnowBlock) {
            return true;
        }
        return block instanceof LiquidBlock && block.getClass() != LiquidBlock.class;
    }

    /** True only for vanilla liquids. 26.3 registers both water and lava as plain
     *  {@link LiquidBlock} instances; a subclass could override the shape, so it is
     *  handled as state-dependent instead. */
    static boolean isPlainLiquidBlock(Block block) {
        return block.getClass() == LiquidBlock.class;
    }
}
