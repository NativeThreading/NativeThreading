package com.github.uright008.pc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentWriteQueueTest {

    private static final long DEADLOCK_GUARD_SECONDS = 5;

    @AfterEach
    void tearDown() {
        ConcurrentWriteQueue.resetForTesting();
    }

    @Test
    void concurrentAddDeferred_noDataLoss() throws Exception {
        int threads = 10;
        int writesPerThread = 100;
        AtomicInteger counter = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        ConcurrentWriteQueue.Phase phase = ConcurrentWriteQueue.beginPhase();

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < writesPerThread; i++) {
                        ConcurrentWriteQueue.INSTANCE.addDeferred(counter::incrementAndGet);
                    }
                    ConcurrentWriteQueue.publishCurrent(phase);
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

        phase.drain();
        assertEquals(threads * writesPerThread, counter.get());
    }

    @Test
    void phasePublishAndDrain_allWritesExecuted() throws Exception {
        int writesPerThread = 50;
        AtomicInteger counter = new AtomicInteger();
        ConcurrentWriteQueue.Phase phase = ConcurrentWriteQueue.beginPhase();

        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < writesPerThread; i++) {
                        ConcurrentWriteQueue.INSTANCE.addDeferred(counter::incrementAndGet);
                    }
                    ConcurrentWriteQueue.publishCurrent(phase);
                } catch (Throwable ignored) {
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        assertTrue(done.await(DEADLOCK_GUARD_SECONDS, TimeUnit.SECONDS));

        phase.drain();
        assertEquals(threads * writesPerThread, counter.get());
    }

    @Test
    void phaseDiscard_noWritesExecuted() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        ConcurrentWriteQueue.Phase phase = ConcurrentWriteQueue.beginPhase();

        ConcurrentWriteQueue.INSTANCE.addDeferred(counter::incrementAndGet);
        ConcurrentWriteQueue.publishCurrent(phase);

        phase.discard();
        assertEquals(0, counter.get());
    }

    @Test
    void phaseDoubleDrain_throws() {
        ConcurrentWriteQueue.Phase phase = ConcurrentWriteQueue.beginPhase();
        phase.drain();

        boolean threw = false;
        try {
            phase.drain();
        } catch (IllegalStateException e) {
            threw = true;
        }
        assertTrue(threw, "second drain should throw IllegalStateException");
    }

    @Test
    void concurrentPhasePublish_noRaceCondition() throws Exception {
        int threads = 8;
        int writesPerThread = 100;
        Set<Integer> received = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        ConcurrentWriteQueue.Phase phase = ConcurrentWriteQueue.beginPhase();

        for (int t = 0; t < threads; t++) {
            int offset = t * writesPerThread;
            new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < writesPerThread; i++) {
                        int value = offset + i;
                        ConcurrentWriteQueue.INSTANCE.addDeferred(() -> received.add(value));
                    }
                    ConcurrentWriteQueue.publishCurrent(phase);
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        assertTrue(done.await(DEADLOCK_GUARD_SECONDS, TimeUnit.SECONDS));
        assertNull(failure.get());

        phase.drain();
        assertEquals(threads * writesPerThread, received.size());
    }
}
