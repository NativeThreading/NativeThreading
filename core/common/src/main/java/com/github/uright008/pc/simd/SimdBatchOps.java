package com.github.uright008.pc.simd;

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

    public static double posX(int slot) { return getEntityDataView().posX()[slot]; }
    public static double posY(int slot) { return getEntityDataView().posY()[slot]; }
    public static double posZ(int slot) { return getEntityDataView().posZ()[slot]; }
    public static double bbMinX(int slot) { return getEntityDataView().bbMinX()[slot]; }
    public static double bbMinY(int slot) { return getEntityDataView().bbMinY()[slot]; }
    public static double bbMinZ(int slot) { return getEntityDataView().bbMinZ()[slot]; }
    public static double bbMaxX(int slot) { return getEntityDataView().bbMaxX()[slot]; }
    public static double bbMaxY(int slot) { return getEntityDataView().bbMaxY()[slot]; }
    public static double bbMaxZ(int slot) { return getEntityDataView().bbMaxZ()[slot]; }

    public static double eyeHeight(int slot) {
        double[][] f = com.github.uright008.vec.core.SoAStore.getFields();
        double[] ey = f[com.github.uright008.vec.core.GeneratedFields.EYE_HEIGHT];
        return (slot >= 0 && slot < ey.length) ? ey[slot] : Double.NaN;
    }

    public static boolean isPrimedTnt(int slot) {
        return com.github.uright008.vec.core.SoAStore.isPrimedTntSlot(slot);
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
            // Position pre-filter: an entity's bbox can only intersect the query
            // box if its feet position lies within an inflated box. Most slots in
            // the capacity are far from the (typically small) explosion AABB, so
            // the 3 position reads + compares skip the 6 bbox reads + compares
            // for the vast majority. MAX_ENTITY_EXTENT bounds the inflation.
            double[] px = view.posX(), py = view.posY(), pz = view.posZ();
            double e = MAX_ENTITY_EXTENT;
            double xLo = qMinX - e, xHi = qMaxX + e;
            double yLo = qMinY - e, yHi = qMaxY + e;
            double zLo = qMinZ - e, zHi = qMaxZ + e;
            int out = 0;
            for (int i = 0; i < count && out < maxResults; i++) {
                double pxi = px[i];
                if (pxi < xLo || pxi > xHi) continue;
                double pyi = py[i];
                if (pyi < yLo || pyi > yHi) continue;
                double pzi = pz[i];
                if (pzi < zLo || pzi > zHi) continue;
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

    // ── Explicit Vector API SIMD ──────────────────

    private static final int SIMD_THRESHOLD = 32768;
    private static final int SPATIAL_THRESHOLD = 131072;
    private static final double MAX_ENTITY_EXTENT = 16.0;

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

}
