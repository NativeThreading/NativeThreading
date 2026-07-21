package com.github.uright008.pc;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpinBlockingQueueTest {

    private static final long DEADLOCK_GUARD_SECONDS = 5;

    @Test
    void takeWakesOneBlockedConsumerWhenTaskArrives() throws Exception {
        SpinBlockingQueue<Integer> queue = new SpinBlockingQueue<>();
        CountDownLatch enteredTake = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Integer> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread consumer = new Thread(() -> {
            enteredTake.countDown();
            try {
                result.set(queue.take());
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                completed.countDown();
            }
        });

        consumer.start();
        try {
            await(enteredTake);
            awaitWaiting(consumer);

            queue.offer(42);

            await(completed);
            assertNull(failure.get());
            assertEquals(42, result.get());
        } finally {
            stop(consumer);
        }
    }

    @Test
    void oneItemCompletesExactlyOneOfMultipleBlockedConsumers() throws Exception {
        SpinBlockingQueue<Integer> queue = new SpinBlockingQueue<>();
        CountDownLatch enteredTake = new CountDownLatch(2);
        CountDownLatch completed = new CountDownLatch(2);
        CountDownLatch oneCompleted = new CountDownLatch(1);
        ConcurrentLinkedQueue<Integer> results = new ConcurrentLinkedQueue<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread first = consumer(queue, enteredTake, completed, oneCompleted, results, failure);
        Thread second = consumer(queue, enteredTake, completed, oneCompleted, results, failure);

        first.start();
        second.start();
        try {
            await(enteredTake);
            awaitWaiting(first);
            awaitWaiting(second);

            queue.put(7);

            await(oneCompleted);
            assertNull(failure.get());
            assertEquals(1, results.size());
            assertEquals(7, results.peek());
            assertEquals(1L, completed.getCount());
        } finally {
            stop(first);
            stop(second);
        }
    }

    @Test
    void timedPollReturnsNullAfterItsTimeout() throws Exception {
        assertNull(new SpinBlockingQueue<Integer>().poll(1, TimeUnit.SECONDS));
    }

    @Test
    void takePropagatesInterruptionWhileWaiting() throws Exception {
        SpinBlockingQueue<Integer> queue = new SpinBlockingQueue<>();
        CountDownLatch enteredTake = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean sawInterruption = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread consumer = new Thread(() -> {
            enteredTake.countDown();
            try {
                queue.take();
            } catch (InterruptedException expected) {
                sawInterruption.set(true);
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                interrupted.countDown();
            }
        });

        consumer.start();
        try {
            await(enteredTake);
            awaitWaiting(consumer);

            consumer.interrupt();

            await(interrupted);
            assertNull(failure.get());
            assertTrue(sawInterruption.get());
        } finally {
            stop(consumer);
        }
    }

    @Test
    void producersAndConsumersDeliverEveryItemExactlyOnce() throws Exception {
        int producerCount = 4;
        int consumerCount = 4;
        int itemsPerProducer = 128;
        SpinBlockingQueue<Integer> queue = new SpinBlockingQueue<>();
        CountDownLatch consumersEntered = new CountDownLatch(consumerCount);
        CountDownLatch consumersFinished = new CountDownLatch(consumerCount);
        CountDownLatch startProducers = new CountDownLatch(1);
        Set<Integer> received = ConcurrentHashMap.newKeySet();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] consumers = new Thread[consumerCount];
        Thread[] producers = new Thread[producerCount];

        for (int consumerIndex = 0; consumerIndex < consumerCount; consumerIndex++) {
            consumers[consumerIndex] = new Thread(() -> {
                consumersEntered.countDown();
                try {
                    for (int itemIndex = 0; itemIndex < itemsPerProducer; itemIndex++) {
                        if (!received.add(queue.take())) {
                            throw new AssertionError("item delivered more than once");
                        }
                    }
                } catch (Throwable throwable) {
                    failure.compareAndSet(null, throwable);
                } finally {
                    consumersFinished.countDown();
                }
            });
            consumers[consumerIndex].start();
        }

        try {
            await(consumersEntered);
            for (Thread consumer : consumers) awaitWaiting(consumer);

            for (int producerIndex = 0; producerIndex < producerCount; producerIndex++) {
                int offset = producerIndex * itemsPerProducer;
                producers[producerIndex] = new Thread(() -> {
                    try {
                        startProducers.await();
                        for (int itemIndex = 0; itemIndex < itemsPerProducer; itemIndex++) {
                            queue.put(offset + itemIndex);
                        }
                    } catch (Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                    }
                });
                producers[producerIndex].start();
            }

            startProducers.countDown();
            await(consumersFinished);
            for (Thread producer : producers) stop(producer);

            assertNull(failure.get());
            assertEquals(producerCount * itemsPerProducer, received.size());
            for (int item = 0; item < producerCount * itemsPerProducer; item++) {
                assertTrue(received.contains(item));
            }
        } finally {
            for (Thread producer : producers) {
                if (producer != null) stop(producer);
            }
            for (Thread consumer : consumers) stop(consumer);
        }
    }

    private static Thread consumer(SpinBlockingQueue<Integer> queue, CountDownLatch enteredTake,
                                   CountDownLatch completed, CountDownLatch oneCompleted,
                                   ConcurrentLinkedQueue<Integer> results,
                                   AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            enteredTake.countDown();
            try {
                results.add(queue.take());
                oneCompleted.countDown();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            } finally {
                completed.countDown();
            }
        });
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(DEADLOCK_GUARD_SECONDS, TimeUnit.SECONDS), "operation did not complete");
    }

    private static void awaitWaiting(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLOCK_GUARD_SECONDS);
        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(Thread.State.WAITING, thread.getState(), "consumer did not block on the queue condition");
    }

    private static void stop(Thread thread) throws InterruptedException {
        thread.interrupt();
        thread.join(TimeUnit.SECONDS.toMillis(DEADLOCK_GUARD_SECONDS));
        assertFalse(thread.isAlive(), "worker did not stop");
    }
}
