package com.github.uright008.pc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Generic parallel dispatch: partitioning, latch, timeout, error collection —
 * all handled by core.
 *
 * <p>Subsystems provide only the task lambda; core manages everything else.</p>
 */
public final class ParallelWorker {

    private static final Logger LOG = LoggerFactory.getLogger("native-threading:worker");
    private static final long ZERO_TIMEOUT_START_GRACE_MILLIS = 100;

    private ParallelWorker() {}

    /**
     * Execute a mapper function in parallel, one worker per input item
     * (no further partitioning).  Use when each task is already a
     * pre-sized batch.
     */
    public static <T, R> List<R> mapEach(
            ExecutorService executor, List<T> tasks,
            Function<T, R> mapper, int timeoutSeconds) {

        int n = tasks.size();
        if (n == 0) return List.of();

        List<R> results = new ArrayList<>(Collections.nCopies(n, null));
        List<Runnable> work = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final int slot = i;
            work.add(() -> {
                results.set(slot, mapper.apply(tasks.get(slot)));
            });
        }
        executePhase(executor, work, timeoutSeconds);
        return results;
    }

    /**
     * Like {@link #mapEach} but groups items into batches. Each batch is dispatched
     * as a single work item, reducing fork/join overhead for fine-grained tasks.
     *
     * @param <T>            input type
     * @param <R>            result type
     * @return flattened list of results in original order
     */
    public static <T, R> List<R> mapBatched(
            ExecutorService executor, List<T> items,
            Function<T, R> mapper, int batchSize, int timeoutSeconds) {

        int n = items.size();
        if (n == 0) return List.of();
        if (batchSize < 1) batchSize = 1;

        int batches = (n + batchSize - 1) / batchSize;
        if (batches == 1) {
            List<R> results = new ArrayList<>(n);
            for (T item : items) results.add(mapper.apply(item));
            return results;
        }

        int workers = Math.min(Runtime.getRuntime().availableProcessors(), batches);
        List<R> results = new ArrayList<>(Collections.nCopies(n, null));
        int[] batchRange = new int[batches + 1];
        for (int b = 0; b <= batches; b++) {
            batchRange[b] = Math.min(b * batchSize, n);
        }

        int perWorker = batches / workers;
        int extra = batches % workers;
        List<Runnable> work = new ArrayList<>(workers);
        int offset = 0;
        for (int w = 0; w < workers; w++) {
            final int start = offset;
            final int end = offset + perWorker + (w < extra ? 1 : 0);
            offset = end;
            work.add(() -> {
                for (int b = start; b < end; b++) {
                    int from = batchRange[b];
                    int to = batchRange[b + 1];
                    for (int i = from; i < to; i++) {
                        results.set(i, mapper.apply(items.get(i)));
                    }
                }
            });
        }
        executePhase(executor, work, timeoutSeconds);
        return results;
    }

    static int computeWorkers(int itemCount) {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        return Math.min(cpuCores, Math.max(2, itemCount / 16));
    }

    public static int autoBatchSize(int itemCount) {
        int workers = computeWorkers(itemCount);
        return Math.max(1, (itemCount + workers - 1) / workers);
    }

    private static void executePhase(ExecutorService executor, List<Runnable> work, int timeoutSeconds) {
        CountDownLatch completion = new CountDownLatch(work.size());
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        List<Future<?>> submitted = new ArrayList<>(work.size());

        for (Runnable task : work) {
            try {
                submitted.add(executor.submit(() -> runWorker(task, completion, firstError)));
            } catch (Throwable submissionFailure) {
                recordFailure(firstError, submissionFailure);
                completion.countDown();
            }
        }

        boolean interrupted = false;
        boolean completed = false;
        try {
            if (timeoutSeconds == 0) {
                completed = completion.await(ZERO_TIMEOUT_START_GRACE_MILLIS, TimeUnit.MILLISECONDS);
            } else {
                completed = completion.await(timeoutSeconds, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interruption) {
            interrupted = true;
        }

        Throwable failure = firstError.get();
        if (!completed || interrupted || failure != null) {
            cancel(submitted);
            boolean quiescent = awaitQuiescence(completion);
            if (interrupted) {
                Thread.currentThread().interrupt();
                failure = new RuntimeException("Workers interrupted", failure);
            } else if (!completed && failure == null) {
                failure = new RuntimeException("Workers timed out after " + timeoutSeconds + "s");
            }
            if (!quiescent) {
                throw new RuntimeException("Workers failed to reach quiescence after cancellation", failure);
            }
            throwAsRuntime(failure);
        }
    }

    private static void runWorker(Runnable task,
                                  CountDownLatch completion, AtomicReference<Throwable> firstError) {
        try {
            SafeLevelAccess.runSafe(task);
        } catch (Throwable workerFailure) {
            recordFailure(firstError, workerFailure);
        } finally {
            completion.countDown();
        }
    }

    private static void recordFailure(AtomicReference<Throwable> firstError, Throwable failure) {
        firstError.compareAndSet(null, failure);
        try {
            LOG.error("Parallel worker failed", failure);
        } catch (Throwable reportingFailure) {
            if (reportingFailure != failure) {
                failure.addSuppressed(reportingFailure);
            }
        }
    }

    private static void cancel(List<Future<?>> submitted) {
        for (Future<?> future : submitted) {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private static boolean awaitQuiescence(CountDownLatch completion) {
        try {
            return completion.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void throwAsRuntime(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new RuntimeException("Worker failed", failure);
    }
}
