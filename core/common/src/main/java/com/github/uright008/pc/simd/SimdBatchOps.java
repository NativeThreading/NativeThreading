package com.github.uright008.pc.simd;

import com.github.uright008.vec.core.GeneratedFields;

/**
 * Batch operations on double[] entity data arrays.
 *
 * <p>All methods use counted loops that HotSpot auto-vectorizes via
 * SuperWord (-XX:+UseSuperWord, on by default). No module dependencies.</p>
 */
public final class SimdBatchOps {

    /** True when the vectorial module's SoA store is on the classpath.
     *  <p>Loaders that do not bundle vectorial (e.g. the NeoForge aggregate)
     *  must route entity queries back to vanilla instead of touching SoA
     *  classes. Gate on class presence only — never on agent injection.</p> */
    public static final boolean VECTORIAL_AVAILABLE = isVectorialLoaded();

    private static boolean isVectorialLoaded() {
        try {
            return Class.forName("com.github.uright008.vec.core.SoAStore") != null;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private SimdBatchOps() {}

    public static int slotCount() {
        return com.github.uright008.vec.core.SoAStore.VIEW.slotCount();
    }

    public static double[][] batchFields() {
        return com.github.uright008.vec.core.SoAStore.getFields();
    }

    public static int[] slotToIdArray() {
        return com.github.uright008.vec.core.SoAStore.getSlotToId();
    }

    /** Whether the entity at {@code slot} is a primed TNT. */
    public static boolean isPrimedTnt(int slot) {
        return com.github.uright008.vec.core.SoAStore.isPrimedTntSlot(slot);
    }

    public static final int POS_X_ORD = GeneratedFields.POSITION_X;
    public static final int POS_Y_ORD = GeneratedFields.POSITION_Y;
    public static final int POS_Z_ORD = GeneratedFields.POSITION_Z;
    public static final int BB_MIN_X_ORD = GeneratedFields.BB_MIN_X;
    public static final int BB_MIN_Y_ORD = GeneratedFields.BB_MIN_Y;
    public static final int BB_MIN_Z_ORD = GeneratedFields.BB_MIN_Z;
    public static final int BB_MAX_X_ORD = GeneratedFields.BB_MAX_X;
    public static final int BB_MAX_Y_ORD = GeneratedFields.BB_MAX_Y;
    public static final int BB_MAX_Z_ORD = GeneratedFields.BB_MAX_Z;
    public static final int EYE_HEIGHT_ORD = GeneratedFields.EYE_HEIGHT;

    /** Squared distance from (cx,cy,cz) to each entity at the given slots. */
    public static void distanceSqBySlotBatch(int[] slots, int count,
                                             double cx, double cy, double cz, double[] dst) {
        double[][] f = com.github.uright008.vec.core.SoAStore.getFields();
        double[] sx = f[GeneratedFields.POSITION_X];
        double[] sy = f[GeneratedFields.POSITION_Y];
        double[] sz = f[GeneratedFields.POSITION_Z];
        // Gather into dst (reuse dst as gather buffer, compute in-place with SIMD)
        for (int i = 0; i < count; i++) {
            int s = slots[i];
            double dx = sx[s] - cx;
            double dy = sy[s] - cy;
            double dz = sz[s] - cz;
            dst[i] = dx * dx + dy * dy + dz * dz;
        }
    }

    /** Collects slots whose axis-aligned bounding box intersects the query box. */
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

}
