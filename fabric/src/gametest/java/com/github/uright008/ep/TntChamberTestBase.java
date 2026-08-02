package com.github.uright008.ep;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared infrastructure for TNT-chamber explosion gametests.
 *
 * <p>The chamber mirrors the benchmark world layout: an obsidian shell
 * enclosing an interior where TNT blocks sit on the center cross. Tests built
 * on this base start from a clean slate: stray entities cleared, a freshly
 * built chamber, and a single {@code Fuse:0} PrimedTnt detonator spawned just
 * above the shell so it drops onto the TNT stack and triggers the reaction.</p>
 */
public abstract class TntChamberTestBase {

    /** Chamber side length in blocks (odd, so a center block exists). */
    protected static final int CHAMBER_SIZE = 5;

    /**
     * Builds the chamber at the given origin: obsidian shell, interior filled
     * with TNT along the center x/z cross of the middle layer and stone
     * elsewhere.
     */
    protected void buildChamber(GameTestHelper helper, BlockPos origin) {
        int half = CHAMBER_SIZE / 2;
        for (int x = 0; x < CHAMBER_SIZE; x++) {
            for (int y = 0; y < CHAMBER_SIZE; y++) {
                for (int z = 0; z < CHAMBER_SIZE; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    boolean shell = x == 0 || x == CHAMBER_SIZE - 1
                            || y == 0 || y == CHAMBER_SIZE - 1
                            || z == 0 || z == CHAMBER_SIZE - 1;
                    if (shell) {
                        helper.setBlock(pos, Blocks.OBSIDIAN);
                    } else {
                        boolean tntCell = (y == half) && (x == half || z == half);
                        BlockState fill = tntCell
                                ? Blocks.TNT.defaultBlockState()
                                : Blocks.STONE.defaultBlockState();
                        helper.setBlock(pos, fill);
                    }
                }
            }
        }
    }

    /** Removes every entity in the level — the backup world is post-explosion. */
    protected void clearEntities(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.getAllEntities().forEach(Entity::discard);
    }

    /**
     * Spawns a {@code Fuse:0} PrimedTnt at the given relative position — it
     * detonates on the next tick, triggering the chamber chain reaction.
     */
    protected void spawnDetonator(GameTestHelper helper, BlockPos pos) {
        ServerLevel level = helper.getLevel();
        BlockPos abs = helper.absolutePos(pos);
        PrimedTnt tnt = new PrimedTnt(level,
                abs.getX() + 0.5, abs.getY() + 0.5, abs.getZ() + 0.5, null);
        tnt.setFuse(0);
        level.addFreshEntity(tnt);
    }

    /**
     * Triggers a chamber explosion. {@link PrimedTnt#tick()} does not reliably
     * explode in the gametest server (the entity is ticked but no world change
     * occurs), so the detonator is placed as a {@code Fuse:0} entity AND a
     * direct {@link ServerLevel#explode} is issued at the chamber center.
     */
    protected void detonateChamber(GameTestHelper helper, BlockPos center) {
        BlockPos abs = helper.absolutePos(center);
        helper.getLevel().explode(null,
                abs.getX() + 0.5, abs.getY() + 0.5, abs.getZ() + 0.5,
                4.0F, false, net.minecraft.world.level.Level.ExplosionInteraction.TNT);
    }

    /** Counts TNT-block states in the chamber volume (post-explosion residue). */
    protected long countTntBlocks(GameTestHelper helper, BlockPos origin) {
        long count = 0;
        for (int x = 0; x < CHAMBER_SIZE; x++) {
            for (int y = 0; y < CHAMBER_SIZE; y++) {
                for (int z = 0; z < CHAMBER_SIZE; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (helper.getBlockState(pos).is(Blocks.TNT)) count++;
                }
            }
        }
        return count;
    }

    /** Counts PrimedTnt entities near the chamber (structure-scoped). */
    protected long countTntEntities(GameTestHelper helper) {
        int half = CHAMBER_SIZE / 2;
        return helper.findEntities(EntityTypes.TNT,
                half, CHAMBER_SIZE, half, CHAMBER_SIZE + 2).size();
    }
}
