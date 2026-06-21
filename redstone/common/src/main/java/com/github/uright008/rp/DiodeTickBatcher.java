package com.github.uright008.rp;

import com.github.uright008.pc.ParallelThreadPool;
import com.github.uright008.pc.ParallelWorker;
import com.github.uright008.pc.SafeOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.ticks.TickPriority;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class DiodeTickBatcher {

    private record DiodeTick(BlockPos pos, BlockState state) {}

    private static final Map<ServerLevel, ConcurrentLinkedQueue<DiodeTick>> PENDING = new ConcurrentHashMap<>();

    private DiodeTickBatcher() {}

    public static void add(ServerLevel level, BlockPos pos, BlockState state) {
        PENDING.computeIfAbsent(level, k -> new ConcurrentLinkedQueue<>()).add(new DiodeTick(pos, state));
    }

    public static void flush(ServerLevel level) {
        ConcurrentLinkedQueue<DiodeTick> queue = PENDING.remove(level);
        if (queue == null || queue.isEmpty()) return;

        List<DiodeTick> ticks = new ArrayList<>();
        DiodeTick dt;
        while ((dt = queue.poll()) != null) {
            if (dt.state.getBlock() instanceof DiodeBlock) {
                ticks.add(dt);
            }
        }
        int n = ticks.size();
        if (n == 0) return;

        if (n < 4) {
            for (DiodeTick t : ticks) tickSingle(level, t.pos, t.state);
            return;
        }

        ParallelWorker.Batch<DiodeTick, Void> batch = new ParallelWorker.Batch<>(ParallelThreadPool.getPool("Redstone"));
        for (DiodeTick t : ticks) batch.add(t);
        batch.flushVoid(t -> tickSingle(level, t.pos, t.state), 5);
    }

    private static void tickSingle(ServerLevel level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof DiodeBlock diode)) return;
        if (diode.isLocked(level, pos, state)) return;

        boolean on = state.getValue(DiodeBlock.POWERED);
        boolean shouldOn = getInputSignal(level, state, pos) > 0;

        if (on && !shouldOn) {
            SafeOps.setBlock(level, pos, state.setValue(DiodeBlock.POWERED, false), 2);
        } else if (!on && !shouldOn) {
            SafeOps.setBlock(level, pos, state.setValue(DiodeBlock.POWERED, true), 2);
            SafeOps.scheduleTick(level, pos, diode, getDelay(state), TickPriority.VERY_HIGH);
        } else if (!on && shouldOn) {
            SafeOps.setBlock(level, pos, state.setValue(DiodeBlock.POWERED, true), 2);
        }
    }

    private static void tickSingle(ServerLevel level, BlockPos pos) {
        tickSingle(level, pos, level.getBlockState(pos));
    }

    private static int getInputSignal(ServerLevel level, BlockState state, BlockPos pos) {
        Direction facing = state.getValue(DiodeBlock.FACING);
        BlockPos targetPos = pos.relative(facing);
        int input = level.getSignal(targetPos, facing);
        if (input >= 15) return input;
        BlockState targetState = level.getBlockState(targetPos);
        return Math.max(input, targetState.is(Blocks.REDSTONE_WIRE)
                ? targetState.getValue(net.minecraft.world.level.block.RedStoneWireBlock.POWER) : 0);
    }

    private static int getDelay(BlockState state) {
        if (state.getBlock() instanceof RepeaterBlock) {
            return state.getValue(RepeaterBlock.DELAY) * 2;
        }
        return 2;
    }
}
