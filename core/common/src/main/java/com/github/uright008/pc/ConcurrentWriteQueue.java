package com.github.uright008.pc;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-local write queue — avoids CAS contention of a single concurrent queue.
 *
 * <p>Each worker thread buffers deferred writes in its own {@link ArrayList},
 * then atomically hands the buffer to a shared drain queue.
 * The main thread drains all buffers sequentially after the parallel phase.</p>
 */
public final class ConcurrentWriteQueue implements WriteQueue {

    public static final ConcurrentWriteQueue INSTANCE = new ConcurrentWriteQueue();

    private final ThreadLocal<List<Runnable>> localQueue =
            ThreadLocal.withInitial(ArrayList::new);
    private final Queue<List<Runnable>> drainQueue = new ConcurrentLinkedQueue<>();

    private ConcurrentWriteQueue() {}

    static Phase beginPhase() {
        return new Phase();
    }

    static void publishCurrent(Phase phase) {
        List<Runnable> writes = INSTANCE.localQueue.get();
        INSTANCE.localQueue.set(new ArrayList<>());
        phase.publish(writes);
    }

    static void discardCurrent() {
        INSTANCE.localQueue.set(new ArrayList<>());
    }

    @Override
    public void addDeferred(Runnable write) {
        localQueue.get().add(write);
    }

    @Override
    public void drainWrites() {
        List<Runnable> current = localQueue.get();
        if (!current.isEmpty()) {
            drainQueue.add(current);
            localQueue.set(new ArrayList<>());
        }
        List<Runnable> batch;
        while ((batch = drainQueue.poll()) != null) {
            for (Runnable r : batch) {
                r.run();
            }
        }
    }

    static final class Phase {
        private final List<List<Runnable>> batches = new ArrayList<>();
        private boolean accepting = true;

        private synchronized void publish(List<Runnable> writes) {
            if (accepting && !writes.isEmpty()) {
                batches.add(writes);
            }
        }

        void drain() {
            List<List<Runnable>> ready;
            synchronized (this) {
                if (!accepting) {
                    throw new IllegalStateException("Deferred-write phase is no longer active");
                }
                accepting = false;
                ready = new ArrayList<>(batches);
                batches.clear();
            }
            for (List<Runnable> batch : ready) {
                for (Runnable write : batch) {
                    write.run();
                }
            }
        }

        void discard() {
            synchronized (this) {
                accepting = false;
                batches.clear();
            }
        }
    }

    /** Clear all pending writes. Intended for test teardown only. */
    public static void resetForTesting() {
        INSTANCE.localQueue.remove();
        INSTANCE.drainQueue.clear();
    }
}
