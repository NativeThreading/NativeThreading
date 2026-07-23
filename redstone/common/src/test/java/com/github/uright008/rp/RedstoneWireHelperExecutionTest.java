package com.github.uright008.rp;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RedstoneWireHelperExecutionTest {

    @Test
    void timedOutPropagationDoesNotReachApply() throws InterruptedException {
        int componentSize = 65;
        ControlledExecutor executor = new ControlledExecutor(2);
        CountDownLatch deadlineExpired = new CountDownLatch(1);
        deadlineExpired.countDown();
        AtomicInteger applyCount = new AtomicInteger();

        boolean completed = RedstoneWireHelper.propagateAndApplyForTesting(
                new int[componentSize], emptyEdges(componentSize), executor, 2,
                completion -> {
                    assertThat(executor.awaitSubmission()).isTrue();
                    return deadlineExpired.getCount() != 0;
                }, powers -> applyCount.incrementAndGet());

        assertThat(completed).isFalse();
        assertThat(applyCount).as("incomplete propagation must not reach apply").hasValue(0);
    }

    @Test
    void rejectedPropagationDoesNotReachApply() {
        AtomicInteger applyCount = new AtomicInteger();

        boolean completed = RedstoneWireHelper.propagateAndApplyForTesting(new int[65], emptyEdges(65),
                command -> { throw new RejectedExecutionException(); }, 2, completion -> true,
                powers -> applyCount.incrementAndGet());

        assertThat(completed).isFalse();
        assertThat(applyCount).as("rejected propagation must not reach apply").hasValue(0);
    }

    @Test
    void interruptedPropagationDoesNotReachApply() {
        AtomicInteger applyCount = new AtomicInteger();

        try {
            boolean completed = RedstoneWireHelper.propagateAndApplyForTesting(new int[65], emptyEdges(65),
                    command -> { }, 2, completion -> { throw new InterruptedException(); },
                    powers -> applyCount.incrementAndGet());

            assertThat(completed).isFalse();
            assertThat(applyCount).as("interrupted propagation must not reach apply").hasValue(0);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void workerFailureDoesNotReachApply() {
        AtomicInteger applyCount = new AtomicInteger();
        int[][] edges = emptyEdges(65);
        edges[0] = null;

        boolean completed = RedstoneWireHelper.propagateAndApplyForTesting(new int[65], edges, Runnable::run, 2,
                completion -> completion.getCount() == 0, powers -> applyCount.incrementAndGet());

        assertThat(completed).isFalse();
        assertThat(applyCount).as("failed propagation must not reach apply").hasValue(0);
    }

    @Test
    void pingPongCopiesUnchangedPowersIntoTheCurrentBuffer() {
        AtomicReference<int[]> appliedPowers = new AtomicReference<>();

        boolean completed = RedstoneWireHelper.propagateAndApplyForTesting(new int[]{15, 0},
                new int[][]{{1}, {0}}, Runnable::run, 1, completion -> true, appliedPowers::set);

        assertThat(completed).isTrue();
        assertThat(appliedPowers.get()).containsExactly(15, 14);
    }

    @Test
    void nextTickCanProcessAnAlreadyProcessed65WireComponent() {
        List<BlockPos> component = component(65);
        assertThat(component).hasSizeGreaterThan(64);
        RedstoneWireHelper.clearProcessed();

        assertThat(RedstoneWireHelper.markComponentForTesting(component)).isTrue();
        assertThat(RedstoneWireHelper.markComponentForTesting(component)).isFalse();

        RedstoneWireHelper.clearProcessed();

        assertThat(RedstoneWireHelper.markComponentForTesting(component)).isTrue();
    }

    @Test
    void changedWireNotifiesItsCenterThenAllSixDirections() {
        BlockPos pos = new BlockPos(4, 70, -9);

        assertThat(RedstoneWireHelper.notificationCentersForTesting(pos)).containsExactlyInAnyOrder(
                pos,
                pos.relative(Direction.DOWN),
                pos.relative(Direction.UP),
                pos.relative(Direction.NORTH),
                pos.relative(Direction.SOUTH),
                pos.relative(Direction.WEST),
                pos.relative(Direction.EAST));
    }

    private static int[][] emptyEdges(int size) {
        int[][] edges = new int[size][];
        for (int index = 0; index < size; index++) {
            edges[index] = new int[0];
        }
        return edges;
    }

    private static List<BlockPos> component(int size) {
        List<BlockPos> positions = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            positions.add(new BlockPos(index, 0, 0));
        }
        return positions;
    }

    private static final class ControlledExecutor implements Executor {
        private final CountDownLatch submitted;

        private ControlledExecutor(int workerCount) {
            submitted = new CountDownLatch(workerCount);
        }

        @Override
        public void execute(Runnable command) {
            submitted.countDown();
        }

        private boolean awaitSubmission() throws InterruptedException {
            return submitted.await(0, TimeUnit.NANOSECONDS);
        }
    }
}
