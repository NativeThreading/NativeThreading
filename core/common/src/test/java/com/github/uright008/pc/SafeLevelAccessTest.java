package com.github.uright008.pc;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeLevelAccessTest {

    @Test
    void startsInactive() {
        assertFalse(SafeLevelAccess.isInSafeZone());
    }

    @Test
    void nestedEntriesRemainActiveUntilTheFinalLeave() {
        SafeLevelAccess.enterSafeZone();
        SafeLevelAccess.enterSafeZone();

        assertTrue(SafeLevelAccess.isInSafeZone());

        SafeLevelAccess.leaveSafeZone();
        assertTrue(SafeLevelAccess.isInSafeZone());

        SafeLevelAccess.leaveSafeZone();
        assertFalse(SafeLevelAccess.isInSafeZone());
    }

    @Test
    void unmatchedLeaveLeavesTheMarkerInactive() {
        assertDoesNotThrow(SafeLevelAccess::leaveSafeZone);
        assertFalse(SafeLevelAccess.isInSafeZone());
    }

    @Test
    void runnableExceptionCleansUpTheMarker() {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> SafeLevelAccess.runSafe(() -> {
                    assertTrue(SafeLevelAccess.isInSafeZone());
                    throw new RuntimeException("expected");
                }));

        assertEquals("expected", failure.getMessage());
        assertFalse(SafeLevelAccess.isInSafeZone());
    }

    @Test
    void supplierReturnsItsResultWhileMakingTheMarkerVisible() {
        AtomicReference<Boolean> markerWasVisible = new AtomicReference<>();

        String result = SafeLevelAccess.runSafe(() -> {
            markerWasVisible.set(SafeLevelAccess.isInSafeZone());
            return "result";
        });

        assertEquals("result", result);
        assertEquals(Boolean.TRUE, markerWasVisible.get());
        assertFalse(SafeLevelAccess.isInSafeZone());
    }
}
