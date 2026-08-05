package com.github.uright008.pc;

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link BlockingQueue} backed by a {@link ConcurrentLinkedQueue} with
 * condition-based waiting for task arrivals.
 *
 * <p>Despite the historical "Spin" name this queue does <b>not</b> spin: takers
 * block on a {@link Condition} until an item is offered. It exists to give
 * {@link ThreadPoolExecutor} a lock-free dequeuing fast path (plain
 * {@code poll()}) plus blocking {@code take()} for the virtual-thread pool.</p>
 */
public final class ConditionBlockingQueue<E> extends AbstractQueue<E> implements BlockingQueue<E> {

    private final ConcurrentLinkedQueue<E> delegate = new ConcurrentLinkedQueue<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    @Override
    public boolean offer(E e) {
        lock.lock();
        try {
            boolean enqueued = delegate.offer(e);
            if (enqueued) notEmpty.signal();
            return enqueued;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            boolean enqueued = delegate.offer(e);
            if (enqueued) notEmpty.signal();
            return enqueued;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public E poll() { return delegate.poll(); }

    @Override
    public E peek() { return delegate.peek(); }

    @Override
    public int size() { return delegate.size(); }

    @Override
    public Iterator<E> iterator() { return delegate.iterator(); }

    @Override
    public void put(E e) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            if (delegate.offer(e)) notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public E take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            E item;
            while ((item = delegate.poll()) == null) {
                notEmpty.await();
            }
            return item;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            E item;
            while ((item = delegate.poll()) == null) {
                if (nanos <= 0) return null;
                nanos = notEmpty.awaitNanos(nanos);
            }
            return item;
        } finally {
            lock.unlock();
        }
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
