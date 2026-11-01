package com.github.uright008.nt.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NtWorkerBatchedWorkerCountTest {

    private static final List<Integer> ITEMS = List.of(1, 2, 3);

    @Test
    void batchedOperationsSubmitOneWorkerPerBatchUpToAvailableProcessors() {
        int expectedWorkers = Math.min(Runtime.getRuntime().availableProcessors(), ITEMS.size());

        CountingExecutorService mapExecutor = new CountingExecutorService();
        assertEquals(ITEMS, NtWorker.mapBatched(mapExecutor, ITEMS, item -> item, 1, 1));
        assertEquals(expectedWorkers, mapExecutor.submittedTasks());
    }

    private static final class CountingExecutorService extends AbstractExecutorService {
        private final AtomicInteger submittedTasks = new AtomicInteger();

        @Override
        public void execute(Runnable command) {
            submittedTasks.incrementAndGet();
            command.run();
        }

        int submittedTasks() {
            return submittedTasks.get();
        }

        @Override
        public void shutdown() { }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
