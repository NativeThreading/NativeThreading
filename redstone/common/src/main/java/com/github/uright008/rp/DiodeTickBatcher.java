package com.github.uright008.rp;

import com.github.uright008.pc.ParallelThreadPool;
import com.github.uright008.pc.ParallelWorker;
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

    private record DiodeSnapshot(BlockPos pos, BlockState state, DiodeBlock diode,
                                 boolean locked, boolean hasInput) {}

    private record DiodeAction(BlockPos pos, BlockState state, DiodeBlock diode,
                               Transition transition) {}

    enum Transition {
        NONE(false, false),
        POWER_OFF(false, false),
        POWER_ON(true, false),
        POWER_ON_AND_RESCHEDULE(true, true);

        final boolean powered;
        final boolean reschedule;

        Transition(boolean powered, boolean reschedule) {
            this.powered = powered;
            this.reschedule = reschedule;
        }
    }

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
            for (DiodeTick tick : ticks) {
                apply(level, computeAction(captureSnapshot(level, tick)));
            }
            return;
        }

        List<DiodeSnapshot> snapshots = new ArrayList<>(n);
        for (DiodeTick tick : ticks) {
            snapshots.add(captureSnapshot(level, tick));
        }

        ParallelWorker.Batch<DiodeSnapshot, DiodeAction> batch = new ParallelWorker.Batch<>(ParallelThreadPool.getPool("Redstone"));
        for (DiodeSnapshot snapshot : snapshots) batch.add(snapshot);
        for (DiodeAction action : batch.flush(DiodeTickBatcher::computeAction, 5)) {
            apply(level, action);
        }
    }

    public static void onLevelUnload(ServerLevel level) {
        PENDING.remove(level);
    }

    private static DiodeSnapshot captureSnapshot(ServerLevel level, DiodeTick tick) {
        BlockState state = tick.state;
        DiodeBlock diode = (DiodeBlock) state.getBlock();
        return new DiodeSnapshot(tick.pos, state, diode,
                diode.isLocked(level, tick.pos, state), getInputSignal(level, state, tick.pos) > 0);
    }

    private static DiodeAction computeAction(DiodeSnapshot snapshot) {
        Transition transition = decideTransition(snapshot.locked, snapshot.state.getValue(DiodeBlock.POWERED),
                snapshot.hasInput);
        return transition == Transition.NONE ? null
                : new DiodeAction(snapshot.pos, snapshot.state, snapshot.diode, transition);
    }

    static Transition decideTransition(boolean locked, boolean powered, boolean hasInput) {
        if (locked) return Transition.NONE;
        if (powered && !hasInput) return Transition.POWER_OFF;
        if (!powered && !hasInput) return Transition.POWER_ON_AND_RESCHEDULE;
        if (!powered) return Transition.POWER_ON;
        return Transition.NONE;
    }

    private static void apply(ServerLevel level, DiodeAction action) {
        if (action == null) return;

        level.setBlock(action.pos, action.state.setValue(DiodeBlock.POWERED, action.transition.powered), 2);
        if (action.transition.reschedule) {
            level.scheduleTick(action.pos, action.diode, getDelay(action.state), TickPriority.VERY_HIGH);
        }
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
