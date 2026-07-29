package com.github.uright008.ep;

/**
 * Implemented on {@code LevelChunkSection} via mixin to track geometry-changing
 * block state modifications. Each successful {@code setBlockState} that alters
 * the block state increments the version, allowing downstream caches to detect
 * when captured section geometry has become stale.
 */
public interface GeometryVersioned {
    long explosion$getGeometryVersion();
}
