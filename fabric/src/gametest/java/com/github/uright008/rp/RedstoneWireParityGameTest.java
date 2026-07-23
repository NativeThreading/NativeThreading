package com.github.uright008.rp;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;

import java.util.ArrayList;
import java.util.List;

public final class RedstoneWireParityGameTest {
    private static final int WIDTH = 13;
    private static final int DEPTH = 5;
    private static final int WIRE_COUNT = WIDTH * DEPTH;
    private static final BlockPos BASELINE = new BlockPos(0, 1, 0);
    private static final BlockPos CANDIDATE = new BlockPos(20, 1, 0);

    @GameTest(maxTicks = 12, padding = 40)
    public void matchesDisabledBaselineFor65Wires(GameTestHelper helper) {
        RedstoneParallelConfig.TestSettings original = RedstoneParallelConfig.configureForTesting(false, false, 64, 2);
        WireSnapshot[] baseline = new WireSnapshot[1];
        boolean[] candidateAccepted = new boolean[1];

        helper.startSequence()
                .thenExecute(() -> {
                    placeFixture(helper, BASELINE);
                    activateFixture(helper, BASELINE);
                })
                .thenIdle(4)
                .thenExecute(() -> baseline[0] = capture(helper, BASELINE))
                .thenExecute(() -> {
                    placeFixture(helper, CANDIDATE);
                    RedstoneWireHelper.clearProcessed();
                    RedstoneParallelConfig.configureForTesting(true, true, 64, 2);
                    activateFixture(helper, CANDIDATE);
                    RedstoneWireHelper.clearProcessed();
                    candidateAccepted[0] = RedstoneWireHelper.tryParallelUpdate(
                            helper.getLevel(), helper.absolutePos(CANDIDATE));
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    try {
                        WireSnapshot candidate = capture(helper, CANDIDATE);
                        helper.assertTrue(candidateAccepted[0],
                                "enabled 65-wire candidate was not accepted by the parallel path");
                        String differences = baseline[0].differences(candidate);
                        helper.assertTrue(differences.isEmpty(), "redstone parity mismatch: " + differences);
                    } finally {
                        RedstoneParallelConfig.restoreForTesting(original);
                    }
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 4, padding = 40)
    public void declinesRemovedInitialWire(GameTestHelper helper) {
        RedstoneParallelConfig.TestSettings original = RedstoneParallelConfig.configureForTesting(true, true, 64, 2);
        BlockPos origin = new BlockPos(0, 1, 0);

        helper.startSequence()
                .thenExecute(() -> {
                    placeFixture(helper, origin);
                    helper.setBlock(origin, Blocks.AIR);
                    RedstoneWireHelper.clearProcessed();
                })
                .thenExecute(() -> {
                    try {
                        helper.assertFalse(RedstoneWireHelper.tryParallelUpdate(helper.getLevel(), helper.absolutePos(origin)),
                                "a removed initial wire must stay on vanilla's removal path");
                    } finally {
                        RedstoneParallelConfig.restoreForTesting(original);
                    }
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 4, padding = 80)
    public void declinesIncompleteWireGraph(GameTestHelper helper) {
        RedstoneParallelConfig.TestSettings original = RedstoneParallelConfig.configureForTesting(false, false, 64, 2);
        BlockPos origin = new BlockPos(0, 1, 0);

        helper.startSequence()
                .thenExecute(() -> {
                    placeOverflowFixture(helper, origin);
                    RedstoneParallelConfig.configureForTesting(true, true, 64, 2);
                    RedstoneWireHelper.clearProcessed();
                })
                .thenExecute(() -> {
                    try {
                        helper.assertFalse(RedstoneWireHelper.tryParallelUpdate(helper.getLevel(), helper.absolutePos(origin)),
                                "an incomplete wire graph must stay on vanilla's path");
                    } finally {
                        RedstoneParallelConfig.restoreForTesting(original);
                    }
                })
                .thenSucceed();
    }

    private static void placeFixture(GameTestHelper helper, BlockPos origin) {
        for (int x = 0; x < WIDTH; x++) {
            for (int z = 0; z < DEPTH; z++) {
                helper.setBlock(origin.offset(x, -1, z), Blocks.STONE);
                helper.setBlock(origin.offset(x, 0, z), Blocks.REDSTONE_WIRE);
            }
        }
        helper.setBlock(origin.offset(WIDTH, -1, DEPTH - 1), Blocks.STONE);
        helper.setBlock(origin.offset(WIDTH + 1, -1, DEPTH - 1), Blocks.STONE);
        helper.setBlock(origin.offset(WIDTH, 0, DEPTH - 1),
                Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.EAST));
        helper.setBlock(origin.offset(WIDTH + 1, 0, DEPTH - 1), Blocks.REDSTONE_LAMP);
    }

    private static void activateFixture(GameTestHelper helper, BlockPos origin) {
        helper.setBlock(origin.offset(WIDTH / 2, -1, DEPTH / 2), Blocks.REDSTONE_BLOCK);
    }

    private static void placeOverflowFixture(GameTestHelper helper, BlockPos origin) {
        int remaining = 4097;
        for (int x = 0; remaining > 0; x++) {
            for (int z = 0; z < 64 && remaining > 0; z++) {
                helper.setBlock(origin.offset(x, -1, z), Blocks.STONE);
                helper.setBlock(origin.offset(x, 0, z), Blocks.REDSTONE_WIRE);
                remaining--;
            }
        }
    }

    private static WireSnapshot capture(GameTestHelper helper, BlockPos origin) {
        int[] powers = new int[WIRE_COUNT];
        for (int x = 0; x < WIDTH; x++) {
            for (int z = 0; z < DEPTH; z++) {
                powers[index(x, z)] = helper.getBlockState(origin.offset(x, 0, z)).getValue(RedStoneWireBlock.POWER);
            }
        }
        boolean downstreamLit = helper.getBlockState(origin.offset(WIDTH, 0, DEPTH - 1)).getValue(DiodeBlock.POWERED);
        return new WireSnapshot(powers, downstreamLit);
    }

    private static int index(int x, int z) {
        return x * DEPTH + z;
    }

    private record WireSnapshot(int[] powers, boolean downstreamLit) {
        private String differences(WireSnapshot candidate) {
            List<String> differences = new ArrayList<>();
            for (int x = 0; x < WIDTH; x++) {
                for (int z = 0; z < DEPTH; z++) {
                    int index = index(x, z);
                    if (powers[index] != candidate.powers[index]) {
                        differences.add("(" + x + "," + z + "):" + powers[index] + "!=" + candidate.powers[index]);
                    }
                }
            }
            if (downstreamLit != candidate.downstreamLit) {
                differences.add("lamp:" + downstreamLit + "!=" + candidate.downstreamLit);
            }
            return String.join(", ", differences);
        }
    }
}
