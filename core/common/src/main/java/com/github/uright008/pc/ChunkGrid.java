package com.github.uright008.pc;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
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

    /** Builds a grid of {@code (2*range+1)²} sections centered on the given
     *  section coordinate. The caller decides the range (the explosion reach,
     *  computed once in {@code ExplosionRayBounds.sectionRange}) so the
     *  coverage physics lives in one place. */
    public ChunkGrid(ServerLevel level, int centerSectionX, int centerSectionZ, int range) {
        this.sizeX = range * 2 + 1;
        this.sizeZ = range * 2 + 1;
        this.minSectionX = centerSectionX - range;
        this.minSectionZ = centerSectionZ - range;
        int totalChunks = sizeX * sizeZ;
        this.chunks = new ChunkAccess[totalChunks];
        this.sections = new LevelChunkSection[totalChunks][];
        this.minSections = new int[totalChunks];

        ChunkSafeAccessor scs = (ChunkSafeAccessor) level.getChunkSource();
        for (int dx = 0; dx < sizeX; dx++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                int idx = dx * sizeZ + dz;
                int cx = minSectionX + dx;
                int cz = minSectionZ + dz;
                ChunkAccess chunk = scs.parallelCore$getChunkSafe(cx, cz);
                if (chunk == null) {
                    // Constructed on the main thread. Vanilla's getBlockState
                    // force-loads/generates any chunk the ray reaches; load the
                    // missing chunk here so the flat view matches what vanilla
                    // would read instead of silently treating it as AIR.
                    chunk = level.getChunk(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
                }
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
}
