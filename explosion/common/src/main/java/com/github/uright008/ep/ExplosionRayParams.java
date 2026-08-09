package com.github.uright008.ep;

import java.util.ArrayList;
import java.util.List;

/** The 1352 ray directions every explosion walks. Static initialisation only —
 *  worker rays consume these precomputed values and never touch an RNG. */
public final class ExplosionRayParams {

    /** One normalized direction (and the 0.3F-scaled step) for each boundary
     *  cell of the 16³ grid. */
    public record RayParam(double xd, double yd, double zd,
                           double stepX, double stepY, double stepZ) {}

    public static final List<RayParam> RAY_PARAMS = generateRayParams();

    private ExplosionRayParams() {}

    private static List<RayParam> generateRayParams() {
        List<RayParam> params = new ArrayList<>();
        for (int xx = 0; xx < 16; xx++) {
            for (int yy = 0; yy < 16; yy++) {
                for (int zz = 0; zz < 16; zz++) {
                    if (xx == 0 || xx == 15 || yy == 0 || yy == 15 || zz == 0 || zz == 15) {
                        // Vanilla computes the direction with FLOAT arithmetic
                        // (15.0F/2.0F literals) then widens to double; step uses
                        // 0.3F. Using double literals here changes the least
                        // significant bits and accumulates to different block
                        // traversals. Reproduce the exact float-then-widen order.
                        double xd = xx / 15.0F * 2.0F - 1.0F;
                        double yd = yy / 15.0F * 2.0F - 1.0F;
                        double zd = zz / 15.0F * 2.0F - 1.0F;
                        double d = Math.sqrt(xd * xd + yd * yd + zd * zd);
                        double nx = xd / d;
                        double ny = yd / d;
                        double nz = zd / d;
                        params.add(new RayParam(nx, ny, nz, nx * 0.3F, ny * 0.3F, nz * 0.3F));
                    }
                }
            }
        }
        return params;
    }
}
