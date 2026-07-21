package com.github.uright008.pc;

import java.util.function.Supplier;

/**
 * Thread-safe Level access via {@link ChunkSafeAccessor}.
 *
 * <p>Vanilla {@code Level.getBlockEntity()} returns null when called from
 * a non-main thread. Each worker thread must call {@link #enterSafeZone()}
 * before its own world access and {@link #leaveSafeZone()} afterwards.
 * Mixins in this mod intercept {@code getBlockEntity} / {@code getBlockState}
 * and use {@link ChunkSafeAccessor} when inside the safe zone.</p>
 *
 * <p>Uses per-thread re-entrant state so only the current thread sees the
 * safe zone after entering it, and nested enter/leave pairs remain balanced.</p>
 *
 * <p>Convenience wrappers {@link #runSafe(Runnable)} and {@link #runSafe(Supplier)}
 * eliminate the enter/try/finally/leave boilerplate.</p>
 */
public final class SafeLevelAccess {

    private static final ThreadLocal<int[]> safeZoneDepth =
            ThreadLocal.withInitial(() -> new int[1]);

    private SafeLevelAccess() {}

    public static void enterSafeZone() {
        int[] depth = safeZoneDepth.get();
        depth[0]++;
    }

    public static void leaveSafeZone() {
        int[] depth = safeZoneDepth.get();
        if (depth[0] > 0) depth[0]--;
    }

    public static boolean isInSafeZone() {
        int[] depth = safeZoneDepth.get();
        return depth[0] > 0;
    }

    public static void runSafe(Runnable action) {
        enterSafeZone();
        try { action.run(); }
        finally { leaveSafeZone(); }
    }

    public static <T> T runSafe(Supplier<T> action) {
        enterSafeZone();
        try { return action.get(); }
        finally { leaveSafeZone(); }
    }
}
