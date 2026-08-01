package com.github.uright008.pc.simd;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

final class VectorApi {

    private VectorApi() {}

    static int intersectAABBSimd(
            double[] minX, double[] minY, double[] minZ,
            double[] maxX, double[] maxY, double[] maxZ,
            int start, int count,
            double qMinX, double qMinY, double qMinZ,
            double qMaxX, double qMaxY, double qMaxZ,
            int[] result, int maxResults) {
        VectorSpecies<Double> S = DoubleVector.SPECIES_PREFERRED;
        int step = S.length();
        int end = start + count;
        int out = 0;

        var vQMinX = DoubleVector.broadcast(S, qMinX);
        var vQMaxX = DoubleVector.broadcast(S, qMaxX);
        var vQMinY = DoubleVector.broadcast(S, qMinY);
        var vQMaxY = DoubleVector.broadcast(S, qMaxY);
        var vQMinZ = DoubleVector.broadcast(S, qMinZ);
        var vQMaxZ = DoubleVector.broadcast(S, qMaxZ);

        int i;
        for (i = start; i <= end - step && out < maxResults; i += step) {
            var mx0 = DoubleVector.fromArray(S, minX, i);
            var mx1 = DoubleVector.fromArray(S, maxX, i);
            var my0 = DoubleVector.fromArray(S, minY, i);
            var my1 = DoubleVector.fromArray(S, maxY, i);
            var mz0 = DoubleVector.fromArray(S, minZ, i);
            var mz1 = DoubleVector.fromArray(S, maxZ, i);

            var hitMask = mx0.compare(VectorOperators.LE, vQMaxX)
                    .and(mx1.compare(VectorOperators.GE, vQMinX))
                    .and(my0.compare(VectorOperators.LE, vQMaxY))
                    .and(my1.compare(VectorOperators.GE, vQMinY))
                    .and(mz0.compare(VectorOperators.LE, vQMaxZ))
                    .and(mz1.compare(VectorOperators.GE, vQMinZ));

            long bits = hitMask.toLong();
            while (bits != 0 && out < maxResults) {
                int lane = Long.numberOfTrailingZeros(bits);
                result[out++] = i + lane;
                bits &= bits - 1;
            }
        }

        for (; i < end && out < maxResults; i++) {
            if (minX[i] <= qMaxX & maxX[i] >= qMinX
              & minY[i] <= qMaxY & maxY[i] >= qMinY
              & minZ[i] <= qMaxZ & maxZ[i] >= qMinZ) {
                result[out++] = i;
            }
        }
        return out;
    }
}
