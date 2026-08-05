package com.github.uright008.vec.core;

import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;

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

    // ── Entity type flags ─────────────────────────

    /**
     * Ordinal of the "is primed TNT" flag. Reuses ordinal 31, one of the two
     * free slots in the generated ordinal space (stuckSpeedMultiplier Y/Z are
     * never written by GeneratedSync nor read by SimdBatchOps). Do not reuse
     * ordinal 30 (STUCK_SPEED_MULTIPLIER_X).
     *
     * <p>unregisterImpl clears every field array to NaN, so this flag is
     * automatically reset when an entity is removed.</p>
     */
    static final int IS_PRIMED_TNT_ORD = 31;

    /** Whether the entity at {@code slot} is a primed TNT (flagged at registration). */
    public static boolean isPrimedTntSlot(int slot) {
        double[] flag = INSTANCE.fields[IS_PRIMED_TNT_ORD];
        return slot >= 0 && slot < flag.length && flag[slot] == 1.0;
    }

    /** Raw primed-TNT flag array for batch readers (call once, index per slot). */
    public static double[] primedTntFlagArray() {
        return INSTANCE.fields[IS_PRIMED_TNT_ORD];
    }

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
        if (entity instanceof PrimedTnt) fields[IS_PRIMED_TNT_ORD][slot] = 1.0;
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
        VarHandle.storeStoreFence();
    }

    // ── Registration (lock-free allocate, lock on expand only) ──
    private int[] growId(int minId) {
        int[] old = idToSlot, next = new int[Math.max(old.length * 2, minId + 4096)];
        System.arraycopy(old, 0, next, 0, old.length);
        Arrays.fill(next, old.length, next.length, -1);
        idToSlot = next;
        return next;
    }

    private static final int MAX_CAPACITY = 1_000_000;

    private void expand(int newCap) {
        synchronized (expandLock) {
            if (newCap <= slotCount) return;
            if (newCap > MAX_CAPACITY) {
                throw new IllegalStateException("SoAStore capacity exceeded: " + newCap + " > " + MAX_CAPACITY);
            }
            for (int i = 0; i < GeneratedFields.COUNT; i++) {
                double[] old = fields[i];
                fields[i] = new double[newCap];
                System.arraycopy(old, 0, fields[i], 0, old.length);
                Arrays.fill(fields[i], old.length, newCap, Double.NaN);
            }
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
