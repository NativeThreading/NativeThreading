package com.github.uright008.ep;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Batch AABB filtering for the explosion entity capture.
 *
 * <p>The capture's spatial query would otherwise test every candidate's
 * bounding box against the blast AABB one object at a time — a scattered
 * {@code getBoundingBox().intersects(bb)} per entity that SuperWord cannot
 * vectorize. This filter instead copies each box into six contiguous
 * {@code double[]} in a first pass, then runs the comparison over those
 * arrays in a second pass. The comparison loop is a plain counted scalar
 * loop over contiguous memory, so SuperWord (on by default) can
 * auto-vectorize it and the second pass reads hot L1 data.
 *
 * <p>Results are bit-identical to {@link AABB#intersects(AABB)}: the same
 * six strict comparisons on the same doubles (edge-touching boxes do not
 * intersect, matching vanilla's {@code <}/{@code >} semantics).
 *
 * <p>Thread safety: the static buffer is only touched on the main thread,
 * one explosion at a time (capture is serial), and refilled before any
 * reuse — the same model as the flat-view buffers.
 */
public final class AabbBatchFilter {

    private static final class Batch {
        final double[][] boxes = new double[6][];
        Entity[] refs;
    }

    private static final AtomicReference<Batch> BATCH_CACHE = new AtomicReference<>();

    private AabbBatchFilter() {}

    @FunctionalInterface
    public interface HitConsumer {
        void accept(Entity entity);
    }

    /** Filters {@code entities} against the query box, invoking {@code out}
     *  for every entity whose bounding box intersects it (strictly), in
     *  iteration order. */
    public static void filter(Collection<Entity> entities,
                              double qMinX, double qMinY, double qMinZ,
                              double qMaxX, double qMaxY, double qMaxZ,
                              HitConsumer out) {
        int n = entities.size();
        if (n == 0) return;
        Batch batch = BATCH_CACHE.getAndSet(null);
        if (batch == null) {
            batch = new Batch();
        }
        if (batch.boxes[0] == null || batch.boxes[0].length < n) {
            for (int a = 0; a < 6; a++) batch.boxes[a] = new double[n];
            batch.refs = new Entity[n];
        }
        double[] bx0 = batch.boxes[0], by0 = batch.boxes[1], bz0 = batch.boxes[2];
        double[] bx1 = batch.boxes[3], by1 = batch.boxes[4], bz1 = batch.boxes[5];
        Entity[] refs = batch.refs;

        int i = 0;
        for (Entity entity : entities) {
            AABB bb = entity.getBoundingBox();
            bx0[i] = bb.minX;
            by0[i] = bb.minY;
            bz0[i] = bb.minZ;
            bx1[i] = bb.maxX;
            by1[i] = bb.maxY;
            bz1[i] = bb.maxZ;
            refs[i] = entity;
            i++;
        }

        // Strict comparisons — bit-identical to AABB.intersects(AABB).
        for (int j = 0; j < n; j++) {
            if (bx0[j] < qMaxX & bx1[j] > qMinX
              & by0[j] < qMaxY & by1[j] > qMinY
              & bz0[j] < qMaxZ & bz1[j] > qMinZ) {
                out.accept(refs[j]);
            }
        }
        BATCH_CACHE.set(batch);
    }
}
