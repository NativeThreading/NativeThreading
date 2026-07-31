package com.github.uright008.pc;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public final class ChunkGrid {

    public static final class SectionRef {
        @Nullable public ChunkAccess chunk;
        @Nullable public LevelChunkSection section;
    }

    private final ChunkAccess[] chunks;
    private final LevelChunkSection[][] sections;
    private final int[] minSections;
    private final int minSectionX;
    private final int minSectionZ;
    private final int sizeX;
    private final int sizeZ;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    public ChunkGrid(ServerLevel level, double centerX, double centerZ, float radius) {
        int scx = SectionPos.blockToSectionCoord((int) Math.floor(centerX));
        int scz = SectionPos.blockToSectionCoord((int) Math.floor(centerZ));
        int range = (int) Math.ceil(radius / 16.0) + 1;
        this.sizeX = range * 2 + 1;
        this.sizeZ = range * 2 + 1;
        this.minSectionX = scx - range;
        this.minSectionZ = scz - range;
        int totalChunks = sizeX * sizeZ;
        this.chunks = new ChunkAccess[totalChunks];
        this.sections = new LevelChunkSection[totalChunks][];
        this.minSections = new int[totalChunks];

        ChunkSafeAccessor scs = (ChunkSafeAccessor) level.getChunkSource();
        for (int dx = 0; dx < sizeX; dx++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                int idx = dx * sizeZ + dz;
                ChunkAccess chunk = scs.parallelCore$getChunkSafe(minSectionX + dx, minSectionZ + dz);
                this.chunks[idx] = chunk;
                if (chunk != null) {
                    int sectionCount = chunk.getSectionsCount();
                    LevelChunkSection[] chunkSections = new LevelChunkSection[sectionCount];
                    for (int i = 0; i < sectionCount; i++) {
                        chunkSections[i] = chunk.getSection(i);
                    }
                    this.sections[idx] = chunkSections;
                    this.minSections[idx] = chunk.getSectionYFromSectionIndex(0);
                }
            }
        }
    }

    @Nullable
    public ChunkAccess getChunk(int sectionX, int sectionZ) {
        int gx = sectionX - minSectionX;
        int gz = sectionZ - minSectionZ;
        if (gx < 0 || gx >= sizeX || gz < 0 || gz >= sizeZ) return null;
        return chunks[gx * sizeZ + gz];
    }

    public BlockState getBlockState(int sectionX, int sectionZ, int blockY, int localX, int localY, int localZ) {
        int gx = sectionX - minSectionX;
        int gz = sectionZ - minSectionZ;
        if (gx < 0 || gx >= sizeX || gz < 0 || gz >= sizeZ) return AIR;

        int idx = gx * sizeZ + gz;
        ChunkAccess chunk = chunks[idx];
        if (chunk == null) return AIR;

        int secIdx = SectionPos.blockToSectionCoord(blockY) - minSections[idx];
        if (secIdx < 0) return AIR;

        LevelChunkSection[] chunkSections = sections[idx];
        if (chunkSections == null || secIdx >= chunkSections.length) return AIR;

        LevelChunkSection section = chunkSections[secIdx];
        if (section == null) {
            section = chunk.getSection(secIdx);
            chunkSections[secIdx] = section;
        }
        return section != null ? section.getBlockState(localX, localY, localZ) : AIR;
    }

    @Nullable
    public LevelChunkSection getSection(int sectionX, int sectionZ, int blockY) {
        int gx = sectionX - minSectionX;
        int gz = sectionZ - minSectionZ;
        if (gx < 0 || gx >= sizeX || gz < 0 || gz >= sizeZ) return null;

        int idx = gx * sizeZ + gz;
        ChunkAccess chunk = chunks[idx];
        if (chunk == null) return null;

        int secIdx = SectionPos.blockToSectionCoord(blockY) - minSections[idx];
        if (secIdx < 0) return null;

        LevelChunkSection[] chunkSections = sections[idx];
        if (chunkSections == null || secIdx >= chunkSections.length) return null;

        LevelChunkSection section = chunkSections[secIdx];
        if (section == null) {
            section = chunk.getSection(secIdx);
            chunkSections[secIdx] = section;
        }
        return section;
    }

    public void getSection(int sectionX, int sectionZ, int blockY, SectionRef out) {
        int gx = sectionX - minSectionX;
        int gz = sectionZ - minSectionZ;
        if (gx < 0 || gx >= sizeX || gz < 0 || gz >= sizeZ) {
            out.chunk = null;
            out.section = null;
            return;
        }

        int idx = gx * sizeZ + gz;
        ChunkAccess chunk = chunks[idx];
        if (chunk == null) {
            out.chunk = null;
            out.section = null;
            return;
        }

        int secIdx = SectionPos.blockToSectionCoord(blockY) - minSections[idx];
        if (secIdx < 0) {
            out.chunk = chunk;
            out.section = null;
            return;
        }

        LevelChunkSection[] chunkSections = sections[idx];
        if (chunkSections == null || secIdx >= chunkSections.length) {
            out.chunk = chunk;
            out.section = null;
            return;
        }

        LevelChunkSection section = chunkSections[secIdx];
        if (section == null) {
            section = chunk.getSection(secIdx);
            chunkSections[secIdx] = section;
        }
        out.chunk = chunk;
        out.section = section;
    }

    public boolean rayIntersectsBlock(double fx, double fy, double fz,
                                       double tx, double ty, double tz) {
        double dx = tx - fx, dy = ty - fy, dz = tz - fz;
        double lenSq = dx * dx + dy * dy + dz * dz;
        if (lenSq < 1.0E-7) return false;

        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);
        double tDeltaX = stepX != 0 ? stepX / dx : Double.MAX_VALUE;
        double tDeltaY = stepY != 0 ? stepY / dy : Double.MAX_VALUE;
        double tDeltaZ = stepZ != 0 ? stepZ / dz : Double.MAX_VALUE;
        double tMaxX = tDeltaX * (stepX > 0 ? 1.0 - Mth.frac(fx) : Mth.frac(fx));
        double tMaxY = tDeltaY * (stepY > 0 ? 1.0 - Mth.frac(fy) : Mth.frac(fy));
        double tMaxZ = tDeltaZ * (stepZ > 0 ? 1.0 - Mth.frac(fz) : Mth.frac(fz));
        int x = Mth.floor(fx), y = Mth.floor(fy), z = Mth.floor(fz);
        int endX = Mth.floor(tx), endY = Mth.floor(ty), endZ = Mth.floor(tz);
        int cx = SectionPos.blockToSectionCoord(x);
        int cz = SectionPos.blockToSectionCoord(z);

        while (true) {
            if (stepX > 0 ? x > endX : (stepX < 0 ? x < endX : false)) break;
            if (stepY > 0 ? y > endY : (stepY < 0 ? y < endY : false)) break;
            if (stepZ > 0 ? z > endZ : (stepZ < 0 ? z < endZ : false)) break;

            BlockState state = getBlockState(cx, cz, y, x & 15, y & 15, z & 15);
            if (!state.isAir()) {
                VoxelShape shape = state.getCollisionShape(null, null);
                if (!shape.isEmpty()) {
                    net.minecraft.world.phys.AABB bb = shape.bounds();
                    if (rayAabbIntersects(fx, fy, fz, tx, ty, tz, bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ))
                        return true;
                }
            }

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) { if (stepX == 0) break; x += stepX; cx = SectionPos.blockToSectionCoord(x); tMaxX += tDeltaX; }
                else                { if (stepZ == 0) break; z += stepZ; cz = SectionPos.blockToSectionCoord(z); tMaxZ += tDeltaZ; }
            } else {
                if (tMaxY < tMaxZ) { if (stepY == 0) break; y += stepY; tMaxY += tDeltaY; }
                else                { if (stepZ == 0) break; z += stepZ; cz = SectionPos.blockToSectionCoord(z); tMaxZ += tDeltaZ; }
            }
        }
        return false;
    }

    public static boolean rayIntersectsOcclusionGrid(double fx, double fy, double fz,
                                                      double tx, double ty, double tz,
                                                      boolean[] grid,
                                                      int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                                      int strideY, int strideZ) {
        double dx = tx - fx, dy = ty - fy, dz = tz - fz;
        double lenSq = dx * dx + dy * dy + dz * dz;
        if (lenSq < 1.0E-7) return false;

        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);
        double tDeltaX = stepX != 0 ? stepX / dx : Double.MAX_VALUE;
        double tDeltaY = stepY != 0 ? stepY / dy : Double.MAX_VALUE;
        double tDeltaZ = stepZ != 0 ? stepZ / dz : Double.MAX_VALUE;
        double tMaxX = tDeltaX * (stepX > 0 ? 1.0 - Mth.frac(fx) : Mth.frac(fx));
        double tMaxY = tDeltaY * (stepY > 0 ? 1.0 - Mth.frac(fy) : Mth.frac(fy));
        double tMaxZ = tDeltaZ * (stepZ > 0 ? 1.0 - Mth.frac(fz) : Mth.frac(fz));
        int x = Mth.floor(fx), y = Mth.floor(fy), z = Mth.floor(fz);
        int endX = Mth.floor(tx), endY = Mth.floor(ty), endZ = Mth.floor(tz);

        while (true) {
            if (stepX > 0 ? x > endX : (stepX < 0 ? x < endX : false)) break;
            if (stepY > 0 ? y > endY : (stepY < 0 ? y < endY : false)) break;
            if (stepZ > 0 ? z > endZ : (stepZ < 0 ? z < endZ : false)) break;

            if (x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ) {
                int idx = (x - minX) + (y - minY) * strideY + (z - minZ) * strideZ;
                if (grid[idx]) return true;
            }

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) { if (stepX == 0) break; x += stepX; tMaxX += tDeltaX; }
                else                { if (stepZ == 0) break; z += stepZ; tMaxZ += tDeltaZ; }
            } else {
                if (tMaxY < tMaxZ) { if (stepY == 0) break; y += stepY; tMaxY += tDeltaY; }
                else                { if (stepZ == 0) break; z += stepZ; tMaxZ += tDeltaZ; }
            }
        }
        return false;
    }

    private static boolean rayAabbIntersects(double fx, double fy, double fz,
                                              double tx, double ty, double tz,
                                              double minX, double minY, double minZ,
                                              double maxX, double maxY, double maxZ) {
        double dirX = tx - fx, dirY = ty - fy, dirZ = tz - fz;
        double min = 0.0, max = 1.0;
        if (dirX == 0) { if (fx < minX || fx > maxX) return false; }
        else {
            double n = (minX - fx) / dirX, f = (maxX - fx) / dirX;
            if (n > f) { double t = n; n = f; f = t; }
            if (n > min) min = n; if (f < max) max = f;
            if (min > max) return false;
        }
        if (dirY == 0) { if (fy < minY || fy > maxY) return false; }
        else {
            double n = (minY - fy) / dirY, f = (maxY - fy) / dirY;
            if (n > f) { double t = n; n = f; f = t; }
            if (n > min) min = n; if (f < max) max = f;
            if (min > max) return false;
        }
        if (dirZ == 0) { if (fz < minZ || fz > maxZ) return false; }
        else {
            double n = (minZ - fz) / dirZ, f = (maxZ - fz) / dirZ;
            if (n > f) { double t = n; n = f; f = t; }
            if (n > min) min = n; if (f < max) max = f;
            if (min > max) return false;
        }
        return true;
    }

}
