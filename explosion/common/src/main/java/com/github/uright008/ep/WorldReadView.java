package com.github.uright008.ep;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Immutable snapshot of world block states and collision geometry for a bounded
 * region. Created on the main thread from version-validated section data, then
 * passed to worker threads as a read-only view.
 *
 * <p>Workers receive only {@code WorldReadView} — never {@code Level},
 * {@code ChunkAccess}, or {@code LevelChunkSection}. The view holds its own
 * copy of block-state data, so mutations to the live world after capture do not
 * affect workers.</p>
 *
 * <p>Implementation notes:</p>
 * <ul>
 *   <li>Block states are stored in a flat array indexed by
 *       {@code (x - minX) + (y - minY) * strideY + (z - minZ) * strideZ}.</li>
 *   <li>Out-of-bounds access returns a default air-like value.</li>
 *   <li>Collision boxes are provided per-cell via
 *       {@link VisibilityCollisionSnapshot} lookup.</li>
 * </ul>
 *
 * @param <B> block state type — {@code Object} in tests, {@code BlockState} in production
 */
public interface WorldReadView<B> {

    /**
     * Returns the block state at the given world coordinates.
     *
     * @param x world x coordinate
     * @param y world y coordinate
     * @param z world z coordinate
     * @return the captured block state, or a default air-like value if out of bounds
     */
    B getBlockState(int x, int y, int z);

    /**
     * Returns whether the block at the given world coordinates is air.
     *
     * @param x world x coordinate
     * @param y world y coordinate
     * @param z world z coordinate
     * @return true if the block is air or out of bounds
     */
    boolean isAir(int x, int y, int z);

    // ── Factory method for tests (uses string names → stubs) ──

    /**
     * Captures a flat block-state array into an immutable view. This factory
     * method is used by tests that operate without the Minecraft runtime.
     *
     * <p>The source array is copied; mutations to the source after this call
     * do not affect the view.</p>
     *
     * @param source      flat array of block-state names (e.g. "air", "stone")
     * @param toState     converter from name to block state object
     * @param isAirCheck  predicate to identify air-like states
     * @param minX        minimum world x coordinate (inclusive)
     * @param minY        minimum world y coordinate (inclusive)
     * @param minZ        minimum world z coordinate (inclusive)
     * @param maxX        maximum world x coordinate (inclusive)
     * @param maxY        maximum world y coordinate (inclusive)
     * @param maxZ        maximum world z coordinate (inclusive)
     * @param strideY     stride for y dimension: {@code maxX - minX + 1}
     * @param strideZ     stride for z dimension: {@code strideY * (maxY - minY + 1)}
     * @return an immutable view over the captured data
     */
    static <T> WorldReadView<T> captureFlat(
            String[] source,
            Function<String, T> toState,
            Predicate<T> isAirCheck,
            String airDefaultName,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            int strideY, int strideZ) {
        @SuppressWarnings("unchecked")
        T[] states = (T[]) new Object[source.length];
        for (int i = 0; i < source.length; i++) {
            states[i] = toState.apply(source[i]);
        }
        T airDefault = toState.apply(airDefaultName);
        return new FlatWorldReadView<>(states, minX, minY, minZ, maxX, maxY, maxZ,
                strideY, strideZ, airDefault, isAirCheck);
    }
}
