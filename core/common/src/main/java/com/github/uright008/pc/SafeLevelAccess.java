package com.github.uright008.pc;

import java.util.function.Supplier;

/**
 * Worker-phase marker.
 *
 * <p><b>This marker has no enforcement power.</b> {@link #isInSafeZone()} does
 * not grant, check, or block any Level/container/entity access — it is a
 * per-thread depth counter that only flags "a worker task is running". Safe
 * world access comes from the threading boundary: workers receive captured,
 * immutable data, and all mutation happens on the main thread. The marker is
 * there for tooling and diagnostics, not as a runtime guard.</p>
 */
public final class SafeLevelAccess {

    private static final ThreadLocal<Integer> SAFE_ZONE_DEPTH = new ThreadLocal<>();

    private SafeLevelAccess() {}

    public static void enterSafeZone() {
        Integer depth = SAFE_ZONE_DEPTH.get();
        SAFE_ZONE_DEPTH.set(depth == null ? 1 : depth + 1);
    }

    public static void leaveSafeZone() {
        Integer depth = SAFE_ZONE_DEPTH.get();
        if (depth == null || depth == 1) {
            SAFE_ZONE_DEPTH.remove();
            return;
        }
        SAFE_ZONE_DEPTH.set(depth - 1);
    }

    public static boolean isInSafeZone() {
        return SAFE_ZONE_DEPTH.get() != null;
    }

    public static void runSafe(Runnable task) {
        enterSafeZone();
        try {
            task.run();
        } finally {
            leaveSafeZone();
        }
    }

    public static <T> T runSafe(Supplier<T> task) {
        enterSafeZone();
        try {
            return task.get();
        } finally {
            leaveSafeZone();
        }
    }
}
