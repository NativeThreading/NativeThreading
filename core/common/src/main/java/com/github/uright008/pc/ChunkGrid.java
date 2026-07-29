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
                    this.sections[idx] = new LevelChunkSection[chunk.getSectionsCount()];
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

}
