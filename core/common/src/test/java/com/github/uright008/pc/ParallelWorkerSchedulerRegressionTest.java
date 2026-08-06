package com.github.uright008.pc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelWorkerSchedulerRegressionTest {

    private static final long DEADLOCK_GUARD_SECONDS = 5;

    private final ControlledExecutor executor = new ControlledExecutor();

    @AfterEach
    void cleanup() throws InterruptedException {
        executor.releaseTasks();
        executor.shutdownNow();
    }

    @Test
    void workerExceptionReleasesCompletionEvenWhenFailureReportingFails() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread phase = startPhase(failure, () -> ParallelWorker.mapEach(
                executor,
                List.of(1),
                ignored -> {
                    throw new LogHostileException();
                },
                1));

        await(executor.taskSubmitted);
        executor.releaseTasks();
        awaitPhase(phase);

        assertInstanceOf(LogHostileException.class, failure.get());
    }

    @Test
    void timedOutWorkIsCancelledBeforeItCanFinish() throws Exception {
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch lateFinishPermit = new CountDownLatch(1);
        CountDownLatch workerInterrupted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread phase = startPhase(failure, () -> ParallelWorker.mapEach(
                executor,
                List.of(1),
                ignored -> {
                    actionStarted.countDown();
                    try {
                        lateFinishPermit.await();
                    } catch (InterruptedException expected) {
                        workerInterrupted.countDown();
                        return null;
                    }
                    return null;
                },
                0));

        await(executor.taskSubmitted);
        executor.releaseTasks();
        await(actionStarted);
        awaitPhase(phase);

        try {
            assertInstanceOf(RuntimeException.class, failure.get());
            assertTrue(workerInterrupted.await(DEADLOCK_GUARD_SECONDS, TimeUnit.SECONDS),
                    "timed-out worker was not cancelled");
        } finally {
            lateFinishPermit.countDown();
        }
    }

    @Test
    void workerMarkerIsVisibleDuringTasksAndCleanedUpBeforeThreadReuse() throws Exception {
        ExecutorService reusedWorker = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<Boolean> markerDuringSuccess = new AtomicReference<>();
            ParallelWorker.mapEach(reusedWorker, List.of(1), ignored -> {
                markerDuringSuccess.set(SafeLevelAccess.isInSafeZone());
                return null;
            }, 1);
            assertEquals(Boolean.TRUE, markerDuringSuccess.get());
            assertFalse(reusedWorker.submit(SafeLevelAccess::isInSafeZone).get());

            AtomicReference<Boolean> markerDuringFailure = new AtomicReference<>();
            assertThrows(RuntimeException.class,
                    () -> ParallelWorker.mapEach(reusedWorker, List.of(1), ignored -> {
                        markerDuringFailure.set(SafeLevelAccess.isInSafeZone());
                        throw new RuntimeException("expected");
                    }, 1));
            assertEquals(Boolean.TRUE, markerDuringFailure.get());
            assertFalse(reusedWorker.submit(SafeLevelAccess::isInSafeZone).get());
        } finally {
            reusedWorker.shutdownNow();
            assertTrue(reusedWorker.awaitTermination(DEADLOCK_GUARD_SECONDS, TimeUnit.SECONDS),
                    "reused worker did not terminate");
        }
    }

    private static Thread startPhase(AtomicReference<Throwable> failure, Runnable action) {
        Thread phase = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        phase.start();
        return phase;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(DEADLOCK_GUARD_SECONDS, TimeUnit.SECONDS), "operation did not complete");
    }

    private static void awaitPhase(Thread phase) throws InterruptedException {
        phase.join(TimeUnit.SECONDS.toMillis(DEADLOCK_GUARD_SECONDS));
        assertTrue(!phase.isAlive(), "scheduler phase did not complete");
    }

    private static final class LogHostileException extends RuntimeException {
        @Override
        public String getMessage() {
            throw new AssertionError("failure reporting must not block completion");
        }
    }

    private static final class ControlledExecutor extends AbstractExecutorService {
        private final CountDownLatch taskSubmitted = new CountDownLatch(1);
        private final CountDownLatch releaseTasks = new CountDownLatch(1);
        private final AtomicReference<Thread> worker = new AtomicReference<>();

        @Override
        public void execute(Runnable command) {
            Thread thread = new Thread(() -> {
                taskSubmitted.countDown();
                try {
                    releaseTasks.await();
                    command.run();
                } catch (InterruptedException expected) {
                    Thread.currentThread().interrupt();
                }
            });
            worker.set(thread);
            thread.start();
        }

        void releaseTasks() {
            releaseTasks.countDown();
        }

        @Override
        public void shutdown() { }

        @Override
        public List<Runnable> shutdownNow() {
            Thread thread = worker.get();
            if (thread != null) thread.interrupt();
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            Thread thread = worker.get();
            return thread == null || !thread.isAlive();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            Thread thread = worker.get();
            if (thread != null) thread.join(unit.toMillis(timeout));
            return isTerminated();
        }
    }
}
