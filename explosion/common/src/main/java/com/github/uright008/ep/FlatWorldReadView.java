package com.github.uright008.ep;

import java.util.function.Predicate;

/**
 * Flat-array implementation of {@link WorldReadView}. Stores block states in
 * a contiguous array indexed by world coordinates, with bounds checking.
 *
 * <p>Created on the main thread; safe to pass to workers because the backing
 * array is copied at construction time.</p>
 *
 * @param <B> block state type
 */
final class FlatWorldReadView<B> implements WorldReadView<B> {

    private final B[] states;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;
    private final int strideY;
    private final int strideZ;
    private final B airDefault;
    private final Predicate<B> isAirCheck;

    FlatWorldReadView(
            B[] states,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            int strideY, int strideZ,
            B airDefault,
            Predicate<B> isAirCheck) {
        // Defensive copy — callers cannot mutate the view after construction
        this.states = java.util.Arrays.copyOf(states, states.length);
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.strideY = strideY;
        this.strideZ = strideZ;
        this.airDefault = airDefault;
        this.isAirCheck = isAirCheck;
    }

    @Override
    public B getBlockState(int x, int y, int z) {
        if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) {
            return airDefault;
        }
        int index = (x - minX) + (y - minY) * strideY + (z - minZ) * strideZ;
        return states[index];
    }

    @Override
    public boolean isAir(int x, int y, int z) {
        B state = getBlockState(x, y, z);
        if (state == null) return true;
        return isAirCheck.test(state);
    }
}
