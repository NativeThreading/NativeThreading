package com.github.uright008.pc.simd;

import java.util.BitSet;
import com.github.uright008.pc.ParallelCoreConfig;

/**
 * Batch operations on double[] entity data arrays.
 *
 * All methods use counted loops that HotSpot auto-vectorizes via
 * SuperWord (-XX:+UseSuperWord, on by default). No module dependencies.
 */
public final class SimdBatchOps {

    /** True when Vectorial mod is loaded and SoA data is available. */
    public static final boolean VECTORIAL_AVAILABLE = isVectorialLoaded();

    private static boolean isVectorialLoaded() {
        try {
            return Class.forName("com.github.uright008.vec.core.SoAStore") != null;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** True when SIMD batch optimizations should be used.
     *  Requires both the config toggle and Vectorial being loaded. */
    public static boolean simdEnabled() {
        return ParallelCoreConfig.simdEnabled() && VECTORIAL_AVAILABLE;
    }

    private SimdBatchOps() {}

    static com.github.uright008.vec.core.EntityDataView getEntityDataView() {
        return com.github.uright008.vec.core.SoAStore.VIEW;
    }

    public static int slotToEntityId(int slot) {
        int[] s2i = com.github.uright008.vec.core.SoAStore.getSlotToId();
        return (slot >= 0 && slot < s2i.length) ? s2i[slot] : -1;
    }

    public static int slotCount() {
        return getEntityDataView().slotCount();
    }

public static void extractPositions(int[] slots, int count,
                                    double[] dstX, double[] dstY, double[] dstZ) {
    double[][] f = com.github.uright008.vec.core.SoAStore.getFields();
    double[] sx = f[com.github.uright008.vec.core.GeneratedFields.POSITION_X];
    double[] sy = f[com.github.uright008.vec.core.GeneratedFields.POSITION_Y];
    double[] sz = f[com.github.uright008.vec.core.GeneratedFields.POSITION_Z];
        for (int i = 0; i < count; i++) {
            int s = slots[i];
            dstX[i] = sx[s];
            dstY[i] = sy[s];
            dstZ[i] = sz[s];
        }
    }

    public static int entityIdToSlot(int entityId) {
        int[] i2s = com.github.uright008.vec.core.SoAStore.getIdToSlot();
        return (entityId >= 0 && entityId < i2s.length) ? i2s[entityId] : -1;
    }

public static void distanceSqBySlotBatch(int[] slots, int count,
                                          double cx, double cy, double cz, double[] dst) {
    double[][] f = com.github.uright008.vec.core.SoAStore.getFields();
    double[] sx = f[com.github.uright008.vec.core.GeneratedFields.POSITION_X];
    double[] sy = f[com.github.uright008.vec.core.GeneratedFields.POSITION_Y];
    double[] sz = f[com.github.uright008.vec.core.GeneratedFields.POSITION_Z];
        // Gather into dst (reuse dst as gather buffer, compute in-place with SIMD)
        for (int i = 0; i < count; i++) {
            int s = slots[i];
            double dx = sx[s] - cx;
            double dy = sy[s] - cy;
            double dz = sz[s] - cz;
            dst[i] = dx * dx + dy * dy + dz * dz;
        }
    }

    public static int intersectAABB(int[] result,
                                     double qMinX, double qMinY, double qMinZ,
                                     double qMaxX, double qMaxY, double qMaxZ) {
        com.github.uright008.vec.core.EntityDataView view =
                com.github.uright008.vec.core.SoAStore.VIEW;
        int count = view.slotCount();
        double[] bx0 = view.bbMinX(), bx1 = view.bbMaxX();
        double[] by0 = view.bbMinY(), by1 = view.bbMaxY();
        double[] bz0 = view.bbMinZ(), bz1 = view.bbMaxZ();
        int maxResults = result.length;

        // Pre-filter by chunk-section for very large datasets.
        // Uses Minecraft's 30M offset to avoid Math.floor for negative coords.
        if (count >= SPATIAL_THRESHOLD) {
            double[] px = view.posX(), py = view.posY(), pz = view.posZ();
            int cxMin = (int)((qMinX + 30_000_000) / 16), cxMax = (int)((qMaxX + 30_000_000) / 16);
            int cyMin = (int)((qMinY + 30_000_000) / 16), cyMax = (int)((qMaxY + 30_000_000) / 16);
            int czMin = (int)((qMinZ + 30_000_000) / 16), czMax = (int)((qMaxZ + 30_000_000) / 16);
            int out = 0;
            for (int i = 0; i < count && out < maxResults; i++) {
                int cx = (int)((px[i] + 30_000_000) / 16);
                if (cx < cxMin || cx > cxMax) continue;
                int cy = (int)((py[i] + 30_000_000) / 16);
                if (cy < cyMin || cy > cyMax) continue;
                int cz = (int)((pz[i] + 30_000_000) / 16);
                if (cz < czMin || cz > czMax) continue;
                if (bx0[i] <= qMaxX & bx1[i] >= qMinX
                  & by0[i] <= qMaxY & by1[i] >= qMinY
                  & bz0[i] <= qMaxZ & bz1[i] >= qMinZ) {
                    result[out++] = i;
                }
            }
            return out;
        }

        if (count < SIMD_THRESHOLD) {
            int out = 0;
            for (int i = 0; i < count && out < maxResults; i++) {
                if (bx0[i] <= qMaxX & bx1[i] >= qMinX
                  & by0[i] <= qMaxY & by1[i] >= qMinY
                  & bz0[i] <= qMaxZ & bz1[i] >= qMinZ) {
                    result[out++] = i;
                }
            }
            return out;
        }
        return intersectAABBSimd(bx0, by0, bz0, bx1, by1, bz1,
                0, count, qMinX, qMinY, qMinZ, qMaxX, qMaxY, qMaxZ, result, maxResults);
    }

    static int intersectAABB(com.github.uright008.vec.core.EntityDataView view,
                                     double qMinX, double qMinY, double qMinZ,
                                     double qMaxX, double qMaxY, double qMaxZ,
                                     int[] result) {
        return intersectAABBBatch(
                view.bbMinX(), view.bbMinY(), view.bbMinZ(),
                view.bbMaxX(), view.bbMaxY(), view.bbMaxZ(),
                0, Math.min(view.slotCount(), result.length),
                qMinX, qMinY, qMinZ, qMaxX, qMaxY, qMaxZ, result);
    }

    public static void distanceSqBatch(double[] srcX, double[] srcY, double[] srcZ,
                                        double cx, double cy, double cz,
                                        double[] dst, int start, int count) {
        if (count < SIMD_THRESHOLD) {
            int end = start + count;
            for (int i = start; i < end; i++) {
                double dx = srcX[i] - cx, dy = srcY[i] - cy, dz = srcZ[i] - cz;
                dst[i] = dx * dx + dy * dy + dz * dz;
            }
            return;
        }
        VectorApi.distanceSqBatch(srcX, srcY, srcZ, cx, cy, cz, dst, start, count);
    }

    public static int intersectAABBBatch(
            double[] minX, double[] minY, double[] minZ,
            double[] maxX, double[] maxY, double[] maxZ,
            int start, int count,
            double qMinX, double qMinY, double qMinZ,
            double qMaxX, double qMaxY, double qMaxZ,
            int[] result) {
        return intersectAABBBatch(minX, minY, minZ, maxX, maxY, maxZ,
                start, count, qMinX, qMinY, qMinZ, qMaxX, qMaxY, qMaxZ, result, Integer.MAX_VALUE);
    }

    public static int intersectAABBBatch(
            double[] minX, double[] minY, double[] minZ,
            double[] maxX, double[] maxY, double[] maxZ,
            int start, int count,
            double qMinX, double qMinY, double qMinZ,
            double qMaxX, double qMaxY, double qMaxZ,
            int[] result, int maxResults) {
        int out = 0;
        int end = start + count;
        for (int i = start; i < end && out < maxResults; i++) {
            if (minX[i] <= qMaxX & maxX[i] >= qMinX
              & minY[i] <= qMaxY & maxY[i] >= qMinY
              & minZ[i] <= qMaxZ & maxZ[i] >= qMinZ) {
                result[out++] = i;
            }
        }
        return out;
    }

    // ── Bitmask AABB ──
    // Phase 1: comparison has highly predictable branch (sparse hits → almost never taken).
    //           No loop-carried dependency on `out` → SuperWord can vectorize comparisons.
    // Phase 2: BitSet.nextSetBit() → O(hits) extraction.

    public static int intersectAABBBitmask(
            double[] minX, double[] minY, double[] minZ,
            double[] maxX, double[] maxY, double[] maxZ,
            int start, int count,
            double qMinX, double qMinY, double qMinZ,
            double qMaxX, double qMaxY, double qMaxZ,
            int[] result, int maxResults) {
        BitSet hits = new BitSet(count);
        int end = start + count;

        for (int i = start; i < end; i++) {
            if (minX[i] <= qMaxX & maxX[i] >= qMinX
              & minY[i] <= qMaxY & maxY[i] >= qMinY
              & minZ[i] <= qMaxZ & maxZ[i] >= qMinZ) {
                hits.set(i - start);
            }
        }

        int out = 0;
        for (int idx = hits.nextSetBit(0); idx >= 0 && out < maxResults; idx = hits.nextSetBit(idx + 1)) {
            result[out++] = start + idx;
        }
        return out;
    }

    // ── Explicit Vector API SIMD ──────────────────

    private static final int SIMD_THRESHOLD = 32768;
    private static final int SPATIAL_THRESHOLD = 131072;

    public static int intersectAABBSimd(
            double[] minX, double[] minY, double[] minZ,
            double[] maxX, double[] maxY, double[] maxZ,
            int start, int count,
            double qMinX, double qMinY, double qMinZ,
            double qMaxX, double qMaxY, double qMaxZ,
            int[] result, int maxResults) {
        if (count < SIMD_THRESHOLD) {
            return intersectAABBBatch(minX, minY, minZ, maxX, maxY, maxZ,
                    start, count, qMinX, qMinY, qMinZ, qMaxX, qMaxY, qMaxZ, result, maxResults);
        }
        return VectorApi.intersectAABBSimd(minX, minY, minZ, maxX, maxY, maxZ,
                start, count, qMinX, qMinY, qMinZ, qMaxX, qMaxY, qMaxZ, result, maxResults);
    }

    /**
     * Finds entity indices whose AABBs may intersect the query.
     * Uses Morton-code spatial ordering + sweep window.
     *
     * @param keys  morton codes (SoAStore.keys)
     * @param count number of slots
     * @param qMinX..qMaxZ query AABB
     * @param result output: candidate indices. Must be at least count long.
     * @return number of candidates
     */
    public static int spatialQuery(long[] keys, int count,
                                    double[] minX, double[] minY, double[] minZ,
                                    double[] maxX, double[] maxY, double[] maxZ,
                                    double qMinX, double qMinY, double qMinZ,
                                    double qMaxX, double qMaxY, double qMaxZ,
                                    int[] result) {
        // Build and sort index array by morton key
        int[] idx = new int[count];
        for (int i = 0; i < count; i++) idx[i] = i;
        sortIndicesByKeys(idx, keys, 0, count - 1);

        // Sweep through sorted indices; check a sliding window
        int out = 0;
        int window = Math.min(64, count / 16 + 8);
        for (int i = 0; i < count; i++) {
            int si = idx[i];
            int limit = Math.min(i + window, count);
            for (int j = Math.max(0, i - window); j < limit; j++) {
                if (i == j) continue;
                int sj = idx[j];
                // AABB intersection check
                if (minX[si] <= maxX[sj] && maxX[si] >= minX[sj]
                 && minY[si] <= maxY[sj] && maxY[si] >= minY[sj]
                 && minZ[si] <= maxZ[sj] && maxZ[si] >= minZ[sj]) {
                    result[out++] = si;
                    break;
                }
            }
        }
        return out;
    }

    private static void sortIndicesByKeys(int[] idx, long[] keys, int lo, int hi) {
        if (lo >= hi) return;
        int mid = (lo + hi) >>> 1;
        long a = keys[idx[lo]], b = keys[idx[mid]], c = keys[idx[hi]];
        int pi = (a < b) ? ((b < c) ? mid : (a < c) ? hi : lo)
                         : ((a < c) ? lo : (b < c) ? hi : mid);
        int tmp = idx[pi]; idx[pi] = idx[hi]; idx[hi] = tmp;
        long pivot = keys[idx[hi]];
        int i = lo - 1;
        for (int j = lo; j < hi; j++) {
            if (keys[idx[j]] < pivot) {
                i++;
                tmp = idx[i]; idx[i] = idx[j]; idx[j] = tmp;
            }
        }
        i++;
        tmp = idx[i]; idx[i] = idx[hi]; idx[hi] = tmp;
        sortIndicesByKeys(idx, keys, lo, i - 1);
        sortIndicesByKeys(idx, keys, i + 1, hi);
    }
}
