package com.github.uright008.ep;

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
}
