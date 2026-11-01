package com.github.uright008.nt.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NtThreadPool {

    private static final Logger LOGGER = LoggerFactory.getLogger("native-threading-core");

    private NtThreadPool() {}

    public static int getParallelism() {
        return CoreConfig.poolParallelism();
    }

    public static PoolImplementation getImplementation() {
        return CoreConfig.poolImplementation();
    }

    @SuppressWarnings("unchecked")
    public static <T extends ExecutorService> T getPool(String name) {
        ExecutorService pool = POOLS.get(name);
        return (T) (pool != null ? pool : POOLS.computeIfAbsent(name, NtThreadPool::createPool));
    }

    /**
     * Shuts down every pool and drops them; the next {@link #getPool(String)} lazily
     * creates a fresh pool with the current configuration.
     *
     * <p>Called when a server stops. On a dedicated server the process ends right
     * after, but on a client the worker threads must not outlive the integrated
     * server session — the client keeps running and may open another world.</p>
     */
    public static void shutdownAll() {
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

        return switch (CoreConfig.poolImplementation()) {
            case FORK_JOIN -> {
                ForkJoinPool.ForkJoinWorkerThreadFactory fjFactory = fjPool -> {
                    ForkJoinWorkerThread t = new ForkJoinWorkerThread(fjPool) {};
                    t.setName("NT-FJ-" + name + "-" + t.getPoolIndex());
                    t.setDaemon(true);
                    return t;
                };
                ForkJoinPool fjp = new ForkJoinPool(size, fjFactory, null, true);
                LOGGER.info("Pool '{}' ready - FORK_JOIN x{}", name, size);
                yield fjp;
            }
            case VIRTUAL -> {
                ThreadFactory vtf = Thread.ofVirtual().name("NT-V-" + name + "-", 0).factory();
                ThreadPoolExecutor vtp = new ThreadPoolExecutor(
                        size, size, 60L, TimeUnit.SECONDS,
                        new ConditionBlockingQueue<>(), vtf);
                vtp.prestartAllCoreThreads();
                LOGGER.info("Pool '{}' ready - VIRTUAL x{}", name, size);
                yield vtp;
            }
        };
    }

    private static final ConcurrentHashMap<String, ExecutorService> POOLS = new ConcurrentHashMap<>();
}
