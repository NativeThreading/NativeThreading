package com.github.uright008.pc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeLevelAccessTest {

    @Test
    void nestedEnterLeaveRetainsZeroDepthStateAfterOuterLeave() throws Exception {
        SafeLevelAccess.enterSafeZone();
        int[] depth = currentDepth();
        try {
            SafeLevelAccess.enterSafeZone();
            assertTrue(SafeLevelAccess.isInSafeZone());

            SafeLevelAccess.leaveSafeZone();
            assertTrue(SafeLevelAccess.isInSafeZone());

            SafeLevelAccess.leaveSafeZone();
            assertFalse(SafeLevelAccess.isInSafeZone());
            assertSame(depth, currentDepth());
        } finally {
            while (SafeLevelAccess.isInSafeZone()) SafeLevelAccess.leaveSafeZone();
        }
    }

    @Test
    void runSafeCleansUpAfterException() {
        assertThrows(IllegalStateException.class, () -> SafeLevelAccess.runSafe(() -> {
            throw new IllegalStateException();
        }));

        assertFalse(SafeLevelAccess.isInSafeZone());
    }

    @Test
    void reusedExecutorThreadIsInactiveBeforeNextTask() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> SafeLevelAccess.runSafe(() -> assertTrue(SafeLevelAccess.isInSafeZone()))).get();
            assertFalse(executor.submit(SafeLevelAccess::isInSafeZone).get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void unmatchedLeaveDoesNotUnderflowActiveState() {
        SafeLevelAccess.leaveSafeZone();
        assertFalse(SafeLevelAccess.isInSafeZone());

        SafeLevelAccess.enterSafeZone();
        try {
            assertTrue(SafeLevelAccess.isInSafeZone());
        } finally {
            SafeLevelAccess.leaveSafeZone();
        }
        assertFalse(SafeLevelAccess.isInSafeZone());
    }

    @SuppressWarnings("unchecked")
    private static int[] currentDepth() throws ReflectiveOperationException {
        Field field = SafeLevelAccess.class.getDeclaredField("safeZoneDepth");
        field.setAccessible(true);
        return ((ThreadLocal<int[]>) field.get(null)).get();
    }
}
