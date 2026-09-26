package com.github.uright008.benchmark;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;

/** Main-thread-only fixture, not a worker snapshot. Mobs are never added to the world. */
record BenchmarkScene(String name, Mob[] mobs, BlockPos[] targets) {
    static BenchmarkScene create(ServerLevel level, String name, int requests) {
        // Covers every 64 + 8 radius navigation region, including corner chunks.
        for (int x = -5; x <= 6; x++) {
            for (int z = -5; z <= 6; z++) {
                level.getChunk(x, z);
                level.setChunkForced(x, z, true);
            }
        }
        for (int x = 0; x <= 32; x++) {
            for (int z = 0; z <= 32; z++) {
                level.setBlock(new BlockPos(x, 79, z), Blocks.STONE.defaultBlockState(), 2);
                boolean wall = x == 0 || x == 32 || z == 0 || z == 32;
                if (name.equals("blocked")) wall |= x == 16;
                if (name.equals("maze")) {
                    // Two staggered gates within the vanilla search budget.
                    wall |= x == 8 && (z < 9 || z > 12);
                    wall |= x == 16 && (z < 19 || z > 22);
                }
                for (int y = 80; y <= 84; y++) {
                    level.setBlock(new BlockPos(x, y, z),
                        (wall ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 2);
                }
            }
        }
        Mob[] mobs = new Mob[requests];
        BlockPos[] targets = new BlockPos[requests];
        for (int i = 0; i < requests; i++) {
            // Detached fixture: the spawning factory rejects zombies in peaceful worlds.
            Mob mob = new Zombie(EntityTypes.ZOMBIE, level);
            mob.setPos(2.5 + (i / 16) % 4, 80, 2.5 + i % 16);
            mob.setOnGround(true);
            mob.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(64);
            mob.getNavigation().updatePathfinderMaxVisitedNodes();
            // No AI ticking, entity RNG, motion, collision, or cached active path.
            mobs[i] = mob;
            targets[i] = new BlockPos(28 - (i / 64) % 3, 80, 16 + i % 8);
        }
        return new BenchmarkScene(name, mobs, targets);
    }
}
