package com.github.uright008.vec.core;

import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.entity.Entity;

/**
 * SoA storage with per-field double[] arrays.
 * Lock-free slot allocation via AtomicInteger CAS stack.
 * Only expand() holds a lock (rare operation).
 *
 * <p>Static API delegates to {@link #INSTANCE} for production.
 * Use {@link #createForTesting(int)} to obtain isolated instances for tests.</p>
 */
public final class SoAStore implements EntityDataView {

    /** Production singleton. All static methods delegate to this. */
    public static SoAStore INSTANCE = new SoAStore();

    /** Singleton view for external consumers. */
    public static final EntityDataView VIEW = INSTANCE;

    // ── Instance fields ──────────────────────────

    private volatile int[] idToSlot;
    private volatile int[] slotToId;
    public volatile int[] idToSlotCache;
    public final double[][] fields;
    private long[] keys;
    private int[] freeSlots;
    private final AtomicInteger freeHead = new AtomicInteger();
    private volatile int slotCount;
    private final Object expandLock = new Object();

    // ── Constructors ─────────────────────────────

    private SoAStore() {
        this(256);
    }

    private SoAStore(int initialCapacity) {
        int cap = Math.max(16, initialCapacity);
        idToSlot = new int[4096];
        Arrays.fill(idToSlot, -1);
        slotToId = new int[cap];
        Arrays.fill(slotToId, -1);
        idToSlotCache = idToSlot;
        fields = new double[GeneratedFields.COUNT][];
        for (int i = 0; i < GeneratedFields.COUNT; i++) {
            fields[i] = new double[cap];
            Arrays.fill(fields[i], Double.NaN);
        }
        keys = new long[cap];
        freeSlots = new int[cap];
        for (int i = 0; i < cap; i++) freeSlots[i] = i;
        freeHead.set(cap);
        slotCount = cap;
    }

    /** Creates an isolated instance for testing. */
    public static SoAStore createForTesting(int capacity) {
        return new SoAStore(capacity);
    }

    /** Resets the production singleton to a fresh instance. */
    public static void resetForTesting() {
        INSTANCE = new SoAStore();
    }

    // ── EntityDataView ────────────────────────────
    @Override public int slotCount() { return slotCount; }
    @Override public double[] posX()   { return fields[GeneratedFields.POSITION_X]; }
    @Override public double[] posY()   { return fields[GeneratedFields.POSITION_Y]; }
    @Override public double[] posZ()   { return fields[GeneratedFields.POSITION_Z]; }
    @Override public double[] bbMinX() { return fields[GeneratedFields.BB_MIN_X]; }
    @Override public double[] bbMinY() { return fields[GeneratedFields.BB_MIN_Y]; }
    @Override public double[] bbMinZ() { return fields[GeneratedFields.BB_MIN_Z]; }
    @Override public double[] bbMaxX() { return fields[GeneratedFields.BB_MAX_X]; }
    @Override public double[] bbMaxY() { return fields[GeneratedFields.BB_MAX_Y]; }
    @Override public double[] bbMaxZ() { return fields[GeneratedFields.BB_MAX_Z]; }
    @Override public int slotForEntity(int id) {
        int[] s = idToSlot;
        return (id >= 0 && id < s.length) ? s[id] : -1;
    }

    // ── Backward-compat: direct array access for SimdBatchOps ──

    /** @deprecated Use {@link EntityDataView} methods instead. Exposed for SimdBatchOps. */
    public static int[] getSlotToId() { return INSTANCE.slotToId; }
    /** @deprecated Use {@link EntityDataView} methods instead. Exposed for SimdBatchOps. */
    public static int[] getIdToSlot() { return INSTANCE.idToSlot; }
    /** @deprecated Use {@link EntityDataView} methods instead. Exposed for SimdBatchOps. */
    public static double[][] getFields() { return INSTANCE.fields; }

    /** Morton-code sort keys. Exposed for SimdBatchOps spatial filtering. */
    public static long[] getKeys() { return INSTANCE.keys; }

    // ── Registration (lock-free allocate, lock on expand only) ──

    public static void register(Entity entity) {
        INSTANCE.registerImpl(entity);
    }

    public static void unregister(Entity entity) {
        INSTANCE.unregisterImpl(entity);
    }

    private void registerImpl(Entity entity) {
        int id = entity.getId();
        int[] slots = idToSlot;
        if (id >= slots.length) slots = growId(id);
        if (slots[id] >= 0) return;

        int slot = allocateSlot();
        slotToId[slot] = id;
        VarHandle.storeStoreFence();
        idToSlot[id] = slot;
        idToSlotCache = idToSlot;
    }

    private void unregisterImpl(Entity entity) {
        int id = entity.getId();
        int[] slots = idToSlot;
        if (id < 0 || id >= slots.length) return;

        int slot;
        if ((slot = slots[id]) < 0) return;
        slots[id] = -1;
        slotToId[slot] = -1;

        for (double[] f : fields) f[slot] = Double.NaN;
        freeSlot(slot);

        VarHandle.storeStoreFence();
        idToSlotCache = idToSlot;
    }

    private int allocateSlot() {
        while (true) {
            int head = freeHead.get();
            if (head == 0) {
                synchronized (expandLock) {
                    if (freeHead.get() == 0) expand(slotCount * 2);
                }
                continue;
            }
            int slot = freeSlots[head - 1];
            if (freeHead.compareAndSet(head, head - 1)) return slot;
        }
    }

    private void freeSlot(int slot) {
        while (true) {
            int head = freeHead.get();
            if (head >= freeSlots.length) {
                synchronized (expandLock) {
                    if (freeHead.get() >= freeSlots.length) expand(freeSlots.length * 2);
                }
                continue;
            }
            freeSlots[head] = slot;
            if (freeHead.compareAndSet(head, head + 1)) return;
        }
    }

    // ── Access ────────────────────────────────────

    public static void setDouble(int entityId, int ordinal, double value) {
        INSTANCE.setDoubleImpl(entityId, ordinal, value);
    }

    public static void setDoubles(int entityId, int[] ordinals, double[] values) {
        INSTANCE.setDoublesImpl(entityId, ordinals, values);
    }

    public static long sortKey(int entityId) {
        return INSTANCE.sortKeyImpl(entityId);
    }

    private void setDoubleImpl(int entityId, int ordinal, double value) {
        int[] slots = idToSlot;
        int slot = (entityId >= 0 && entityId < slots.length) ? slots[entityId] : -1;
        if (slot < 0) return;
        fields[ordinal][slot] = value;
    }

    private void setDoublesImpl(int entityId, int[] ordinals, double[] values) {
        int[] slots = idToSlot;
        int slot = (entityId >= 0 && entityId < slots.length) ? slots[entityId] : -1;
        if (slot < 0) return;
        for (int i = 0; i < ordinals.length; i++) fields[ordinals[i]][slot] = values[i];
        if (ordinals[0] == GeneratedFields.POSITION_X) updateKey(slot);
        VarHandle.storeStoreFence();
    }

    private long sortKeyImpl(int entityId) {
        int[] slots = idToSlot;
        int slot = (entityId >= 0 && entityId < slots.length) ? slots[entityId] : -1;
        return slot >= 0 ? keys[slot] : 0;
    }

    // ── Morton key ────────────────────────────────
    private void updateKey(int slot) {
        double x = fields[GeneratedFields.POSITION_X][slot];
        double y = fields[GeneratedFields.POSITION_Y][slot];
        double z = fields[GeneratedFields.POSITION_Z][slot];
        if (Double.isNaN(x)) { keys[slot] = 0; return; }
        long ix = quantize(x), iy = quantize(y), iz = quantize(z);
        keys[slot] = splitBy3(ix) | (splitBy3(iy) << 1) | (splitBy3(iz) << 2);
    }
    private static long quantize(double v) {
        return (long)((v + 30_000_000.0) * (1L << 21) / 60_000_000.0) & ((1L << 21) - 1);
    }
    private static long splitBy3(long x) {
        x &= 0x1fffffL;
        x = (x | x << 32) & 0x1f00000000ffffL;
        x = (x | x << 16) & 0x1f0000ff0000ffL;
        x = (x | x << 8)  & 0x100f00f00f00f00fL;
        x = (x | x << 4)  & 0x10c30c30c30c30c3L;
        x = (x | x << 2)  & 0x1249249249249249L;
        return x;
    }

    // ── Internal ─────────────────────────────────
    private int[] growId(int minId) {
        int[] old = idToSlot, next = new int[Math.max(old.length * 2, minId + 4096)];
        System.arraycopy(old, 0, next, 0, old.length);
        Arrays.fill(next, old.length, next.length, -1);
        idToSlot = next;
        return next;
    }

    private void expand(int newCap) {
        synchronized (expandLock) {
            if (newCap <= slotCount) return;
            for (int i = 0; i < GeneratedFields.COUNT; i++) {
                double[] old = fields[i];
                fields[i] = new double[newCap];
                System.arraycopy(old, 0, fields[i], 0, old.length);
                Arrays.fill(fields[i], old.length, newCap, Double.NaN);
            }
            long[] oldKeys = keys;
            keys = new long[newCap];
            System.arraycopy(oldKeys, 0, keys, 0, oldKeys.length);
            int[] oldSlotToId = slotToId;
            slotToId = new int[newCap];
            System.arraycopy(oldSlotToId, 0, slotToId, 0, oldSlotToId.length);
            Arrays.fill(slotToId, oldSlotToId.length, newCap, -1);
            freeSlots = Arrays.copyOf(freeSlots, newCap);
            for (int i = slotCount; i < newCap; i++) freeSlots[freeHead.getAndIncrement()] = i;
            slotCount = newCap;
        }
    }
}
