package com.github.uright008.pc;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * {@link BlockingQueue} that spin-waits instead of parking threads.
 * Workers spin for up to 100µs, then parkNanos(1ms), avoiding the
 * pthread_cond_wait kernel transition on short idle periods.
 */
public final class SpinBlockingQueue<E> extends AbstractQueue<E> implements BlockingQueue<E> {

    private final ConcurrentLinkedQueue<E> delegate = new ConcurrentLinkedQueue<>();

    @Override
    public boolean offer(E e) { return delegate.offer(e); }

    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) { return delegate.offer(e); }

    @Override
    public E poll() { return delegate.poll(); }

    @Override
    public E peek() { return delegate.peek(); }

    @Override
    public int size() { return delegate.size(); }

    @Override
    public Iterator<E> iterator() { return delegate.iterator(); }

    @Override
    public void put(E e) { delegate.offer(e); }

    @Override
    public E take() throws InterruptedException {
        E item;
        while ((item = delegate.poll()) == null) {
            if (Thread.interrupted()) throw new InterruptedException();
            LockSupport.parkNanos(1_000_000);
        }
        return item;
    }

    @Override
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + nanos;
        E item;
        while ((item = delegate.poll()) == null) {
            if (Thread.interrupted()) throw new InterruptedException();
            if (System.nanoTime() > deadline) return null;
            LockSupport.parkNanos(100_000);
        }
        return item;
    }

    @Override
    public int remainingCapacity() { return Integer.MAX_VALUE; }

    @Override
    public int drainTo(Collection<? super E> c) {
        int n = 0;
        E e;
        while ((e = delegate.poll()) != null) { c.add(e); n++; }
        return n;
    }

    @Override
    public int drainTo(Collection<? super E> c, int max) {
        int n = 0;
        E e;
        while (n < max && (e = delegate.poll()) != null) { c.add(e); n++; }
        return n;
    }
}
