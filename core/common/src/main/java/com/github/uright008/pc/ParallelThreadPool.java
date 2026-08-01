package com.github.uright008.pc;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ParallelThreadPool {

    private static final Logger LOGGER = LoggerFactory.getLogger("parallel-core");

    private ParallelThreadPool() {}

    public static int getParallelism() {
        return ParallelCoreConfig.poolParallelism();
    }

    public static PoolImplementation getImplementation() {
        return ParallelCoreConfig.poolImplementation();
    }

    @SuppressWarnings("unchecked")
    public static <T extends ExecutorService> T getPool(String name) {
        ExecutorService pool = POOLS.get(name);
        return (T) (pool != null ? pool : POOLS.computeIfAbsent(name, ParallelThreadPool::createPool));
    }

    public static void recreateAll() {
        POOLS.values().forEach(pool -> {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                    pool.awaitTermination(1, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        });
        POOLS.clear();
    }

    private static ExecutorService createPool(String name) {
        int size = getParallelism();

        return switch (ParallelCoreConfig.poolImplementation()) {
            case THREAD_POOL -> {
                ForkJoinPool.ForkJoinWorkerThreadFactory factory = pool -> {
                    ForkJoinWorkerThread t = new ForkJoinWorkerThread(pool) {};
                    t.setName("Parallel-" + name + "-" + COUNTER.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                };
                ForkJoinPool fjp = new ForkJoinPool(size, factory, null, true);
                LOGGER.info("Pool '{}' ready - THREAD_POOL x{} (async FJ)", name, size);
                yield fjp;
            }
            case FORK_JOIN -> {
                ForkJoinPool.ForkJoinWorkerThreadFactory fjFactory = fjPool -> {
                    ForkJoinWorkerThread t = new ForkJoinWorkerThread(fjPool) {};
                    t.setName("ParallelFJ-" + name + "-" + t.getPoolIndex());
                    t.setDaemon(true);
                    return t;
                };
                ForkJoinPool fjp = new ForkJoinPool(size, fjFactory, null, true);
                LOGGER.info("Pool '{}' ready - FORK_JOIN x{}", name, size);
                yield fjp;
            }
            case VIRTUAL -> {
                ThreadFactory vtf = Thread.ofVirtual().name("ParallelV-" + name + "-", 0).factory();
                ThreadPoolExecutor vtp = new ThreadPoolExecutor(
                        size, size, 60L, TimeUnit.SECONDS,
                        new SpinBlockingQueue<>(), vtf);
                vtp.prestartAllCoreThreads();
                LOGGER.info("Pool '{}' ready - VIRTUAL x{}", name, size);
                yield vtp;
            }
        };
    }

    private static final ConcurrentHashMap<String, ExecutorService> POOLS = new ConcurrentHashMap<>();
    private static final AtomicInteger COUNTER = new AtomicInteger(0);
}
