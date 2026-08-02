package com.github.uright008.ep;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared infrastructure for TNT-chamber explosion gametests.
 *
 * <p>The chamber mirrors the benchmark world layout: an obsidian shell
 * enclosing an interior filled with TNT and stone. Tests built on this base
 * build the chamber programmatically (no command blocks — the benchmark world's
 * command-block fillers are unnecessary), clear stray entities, detonate, wait
 * 10 seconds, and assert the obsidian interior is empty: no leftover blocks and
 * no item entities.</p>
 */
public abstract class TntChamberTestBase {

    /** Chamber side length in blocks (odd, so a center block exists). */
    protected static final int CHAMBER_SIZE = 5;

    /** Ticks to wait after detonation before asserting the interior is empty. */
    protected static final int DETONATION_SETTLE_TICKS = 200; // 10s

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
     * Detonates an explosion at the chamber center. The benchmark world relies
     * on command blocks to fill the chamber; here a direct
     * {@link ServerLevel#explode} is the trigger (PrimedTnt tick does not
     * reliably explode in the gametest server).
     */
    protected void detonateChamber(GameTestHelper helper, BlockPos center) {
        BlockPos abs = helper.absolutePos(center);
        helper.getLevel().explode(null,
                abs.getX() + 0.5, abs.getY() + 0.5, abs.getZ() + 0.5,
                4.0F, false, net.minecraft.world.level.Level.ExplosionInteraction.TNT);
    }

    /**
     * Returns a description of anything found inside the obsidian shell after
     * detonation — non-air blocks (excluding the shell itself) and item
     * entities. Empty string means the interior is clean.
     */
    protected String interiorFindings(GameTestHelper helper, BlockPos origin) {
        StringBuilder findings = new StringBuilder();
        for (int x = 0; x < CHAMBER_SIZE; x++) {
            for (int y = 0; y < CHAMBER_SIZE; y++) {
                for (int z = 0; z < CHAMBER_SIZE; z++) {
                    boolean shell = x == 0 || x == CHAMBER_SIZE - 1
                            || y == 0 || y == CHAMBER_SIZE - 1
                            || z == 0 || z == CHAMBER_SIZE - 1;
                    if (shell) continue;
                    BlockPos pos = origin.offset(x, y, z);
                    BlockState state = helper.getBlockState(pos);
                    if (!state.isAir()) {
                        findings.append("block ").append(pos).append('=')
                                .append(state).append("; ");
                    }
                }
            }
        }
        int half = CHAMBER_SIZE / 2;
        var items = helper.findEntities(EntityTypes.ITEM,
                half, half, half, CHAMBER_SIZE);
        if (!items.isEmpty()) {
            findings.append(items.size()).append(" item entities; ");
        }
        return findings.toString();
    }

    /** True if the obsidian interior holds no blocks and no item entities. */
    protected boolean interiorIsEmpty(GameTestHelper helper, BlockPos origin) {
        return interiorFindings(helper, origin).isEmpty();
    }
}
