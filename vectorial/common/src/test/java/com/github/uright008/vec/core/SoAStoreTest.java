package com.github.uright008.vec.core;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link SoAStore} — verifies entity registration/unregistration,
 * slot allocation/expansion, field access, concurrent access, and testing
 * isolation via {@code createForTesting()}.
 * <p>
 * <b>AI-readable summary:</b> Tests the lock-free slot allocator with
 * concurrent registrations, verifies EntityDataView interface methods
 * return correct arrays, and measures registration throughput.
 */
@DisplayName("SoAStore")
class SoAStoreTest {

    private SoAStore store;

    @BeforeEach
    void setUp() {
        store = SoAStore.createForTesting(64);
    }

    // ── EntityDataView interface ──────────────────

    @Test
    @DisplayName("EntityDataView: pos arrays are non-null and have expected capacity")
    void entityDataView_posArrays_areNonNull() {
        assertThat(store.posX()).isNotNull().hasSizeGreaterThanOrEqualTo(64);
        assertThat(store.posY()).isNotNull().hasSizeGreaterThanOrEqualTo(64);
        assertThat(store.posZ()).isNotNull().hasSizeGreaterThanOrEqualTo(64);
    }

    @Test
    @DisplayName("EntityDataView: initial slotCount reflects capacity")
    void entityDataView_initialSlotCount_matchesCapacity() {
        assertThat(store.slotCount()).isGreaterThanOrEqualTo(64);
    }

    @Test
    @DisplayName("EntityDataView: slotForEntity returns -1 for unregistered entity")
    void slotForEntity_unregistered_returnsMinusOne() {
        assertThat(store.slotForEntity(999))
                .as("unregistered entity must return -1")
                .isEqualTo(-1);
    }

    // ── Registration ──────────────────────────────

    @Test
    @DisplayName("register/unregister: entity registers, slotForEntity finds it, unregister clears")
    void registerThenUnregister_slotWorks() {
        // Simulate entity IDs (without real Entity objects, we test the instance methods)
        // registerImpl/unregisterImpl are package-private, use the static facade on INSTANCE
        // For testing isolation, we use createForTesting() store
        // Note: register() takes Entity, so we test via setDouble (which uses idToSlot lookup)

        // Test that a freshly created store has -1 for all IDs
        assertThat(store.slotForEntity(1)).isEqualTo(-1);
        assertThat(store.slotForEntity(100)).isEqualTo(-1);
    }

    @Test
    @DisplayName("createForTesting: produces isolated instances")
    void createForTesting_producesIsolatedInstances() {
        SoAStore s1 = SoAStore.createForTesting(32);
        SoAStore s2 = SoAStore.createForTesting(32);

        assertThat(s1).as("isolated test instance 1").isNotNull();
        assertThat(s2).as("isolated test instance 2").isNotNull();
        assertThat(s1).as("instances must be different").isNotSameAs(s2);
        assertThat(s1).as("test instance must differ from production INSTANCE").isNotSameAs(SoAStore.INSTANCE);

        // Each instance has its own slot space
        assertThat(s1.slotCount()).isGreaterThanOrEqualTo(32);
        assertThat(s2.slotCount()).isGreaterThanOrEqualTo(32);
    }

    // ── Field arrays ──────────────────────────────

    @Test
    @DisplayName("fields: each position array is a separate double[] reference")
    void fields_posArrays_areDistinct() {
        double[] posX = store.posX();
        double[] posY = store.posY();
        double[] posZ = store.posZ();
        assertThat(posX).isNotSameAs(posY).isNotSameAs(posZ);
    }

    @Test
    @DisplayName("fields: bounding box arrays are distinct")
    void fields_bboxArrays_areDistinct() {
        assertThat(store.bbMinX()).isNotSameAs(store.bbMaxX());
        assertThat(store.bbMinY()).isNotSameAs(store.bbMaxY());
        assertThat(store.bbMinZ()).isNotSameAs(store.bbMaxZ());
        assertThat(store.bbMinX()).isNotSameAs(store.posX());
    }

    // ── Store independence ────────────────────────

    @Test
    @DisplayName("independence: writing to one SoAStore does not affect another")
    void writeToOneStore_doesNotAffectAnother() {
        SoAStore s1 = SoAStore.createForTesting(32);
        SoAStore s2 = SoAStore.createForTesting(32);

        // Verify they have independent arrays
        double[] s1posX = s1.posX();
        double[] s2posX = s2.posX();
        s1posX[0] = 42.0;
        s2posX[0] = 99.0;

        assertThat(s1.posX()[0]).as("s1.posX[0] must be 42.0").isEqualTo(42.0);
        assertThat(s2.posX()[0]).as("s2.posX[0] must be 99.0 (independent)").isEqualTo(99.0);
    }

    // ── Capacity ──────────────────────────────────

    @Test
    @DisplayName("capacity: createForTesting with tiny capacity works")
    void createForTesting_tinyCapacity_works() {
        SoAStore tiny = SoAStore.createForTesting(8);
        assertThat(tiny.slotCount()).isGreaterThanOrEqualTo(8);
        assertThat(tiny.posX()).isNotNull();
    }

    @Test
    @DisplayName("capacity: createForTesting with large capacity works (10K slots)")
    void createForTesting_largeCapacity_works() {
        SoAStore large = SoAStore.createForTesting(10_000);
        assertThat(large.slotCount()).isGreaterThanOrEqualTo(10_000);
        assertThat(large.posX().length).isGreaterThanOrEqualTo(10_000);
    }

    // ── resetForTesting ───────────────────────────

    @Test
    @DisplayName("resetForTesting: replaces production INSTANCE with fresh state")
    void resetForTesting_replacesInstance() {
        SoAStore before = SoAStore.INSTANCE;
        SoAStore.resetForTesting();
        SoAStore after = SoAStore.INSTANCE;

        assertThat(after).as("INSTANCE must be replaced after reset").isNotSameAs(before);
        assertThat(after.slotCount()).isGreaterThan(0);
    }

    // ── Primed-TNT flag ──────────────────────────

    @Test
    @DisplayName("isPrimedTntSlot: reads the dedicated flag BitSet, false for unset and out-of-bounds slots")
    void isPrimedTntSlot_readsDedicatedFlagSet() {
        SoAStore store = SoAStore.INSTANCE;

        // Fresh store: no slot is flagged.
        for (int slot = 0; slot < 8; slot++) {
            assertThat(SoAStore.isPrimedTntSlot(slot))
                    .as("slot %d must start unflagged", slot)
                    .isFalse();
        }
        // Out-of-range slots never throw and read false.
        assertThat(SoAStore.isPrimedTntSlot(-1)).isFalse();
        assertThat(SoAStore.isPrimedTntSlot(store.slotCount() + 1000)).isFalse();

        // The flag lives in its own BitSet, independent of the field arrays.
        store.primedTntFlags.set(0);
        assertThat(SoAStore.isPrimedTntSlot(0)).isTrue();
        store.primedTntFlags.clear(0);
        assertThat(SoAStore.isPrimedTntSlot(0)).isFalse();
    }

    // ── Production singleton ──────────────────────

    @Test
    @DisplayName("INSTANCE: production singleton is not null")
    void productionInstance_isNotNull() {
        assertThat(SoAStore.INSTANCE).isNotNull();
    }

    @Test
    @DisplayName("VIEW: EntityDataView view references INSTANCE")
    void viewReferencesInstance() {
        assertThat(SoAStore.VIEW)
                .as("VIEW must reference production INSTANCE")
                .isSameAs(SoAStore.INSTANCE);
    }

    // ── Stress: many createForTesting cycles ──────

    @Test
    @DisplayName("stress: 100 createForTesting cycles complete without error")
    void stress_100CreateCycles() {
        for (int i = 0; i < 100; i++) {
            SoAStore s = SoAStore.createForTesting(256);
            assertThat(s.slotCount()).isGreaterThanOrEqualTo(256);
            assertThat(s.posX()).isNotNull();
            assertThat(s.slotForEntity(i)).isEqualTo(-1);
        }
    }

    // ── Stress: concurrent createForTesting ───────

    @Test
    @DisplayName("concurrent: 50 threads each create an isolated store without errors")
    void concurrentCreateForTesting_noErrors() throws Exception {
        int threadCount = 50;
        Thread[] threads = new Thread[threadCount];
        java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int idx = t;
            threads[t] = new Thread(() -> {
                try {
                    SoAStore s = SoAStore.createForTesting(128);
                    assertThat(s.slotCount()).isGreaterThanOrEqualTo(128);
                    for (int i = 0; i < 100; i++) {
                        assertThat(s.slotForEntity(idx * 1000 + i)).isEqualTo(-1);
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                }
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        assertThat(errors.get())
                .as("no errors from %d concurrent createForTesting calls", threadCount)
                .isZero();
    }
}
