package com.github.uright008.pc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelThreadPoolTest {

    private static final long DEADLOCK_GUARD_SECONDS = 5;

    @BeforeEach
    void setUp() {
        ParallelCoreConfig.resetForTesting(ConfigStorage.inMemory());
        ParallelCoreConfig.init();
    }

    @Test
    void getPool_sameName_returnsSameInstance() {
        ExecutorService pool1 = ParallelThreadPool.getPool("test-same");
        ExecutorService pool2 = ParallelThreadPool.getPool("test-same");
        assertSame(pool1, pool2);
    }

    @Test
    void getPool_differentNames_returnsDifferentInstances() {
        ExecutorService pool1 = ParallelThreadPool.getPool("test-diff-a");
        ExecutorService pool2 = ParallelThreadPool.getPool("test-diff-b");
        assertTrue(pool1 != pool2);
    }

    @Test
    void getPool_notNull() {
        assertNotNull(ParallelThreadPool.getPool("test-notnull"));
    }

    @Test
    void concurrentGetPool_noRaceCondition() throws Exception {
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService[] results = new ExecutorService[threads];

        for (int t = 0; t < threads; t++) {
            final int index = t;
            new Thread(() -> {
                try {
                    start.await();
                    results[index] = ParallelThreadPool.getPool("test-concurrent");
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        assertTrue(done.await(DEADLOCK_GUARD_SECONDS, TimeUnit.SECONDS), "threads did not complete");
        assertNull(failure.get());

        for (int i = 1; i < threads; i++) {
            assertSame(results[0], results[i], "all threads should get the same pool instance");
        }
    }

    @Test
    void recreateAll_poolsRecreated() {
        ExecutorService pool1 = ParallelThreadPool.getPool("test-recreate");
        ParallelThreadPool.recreateAll();
        ExecutorService pool2 = ParallelThreadPool.getPool("test-recreate");
        assertTrue(pool1 != pool2, "pool should be a new instance after recreateAll");
        assertTrue(pool1.isShutdown(), "old pool should be shutdown");
    }
}
