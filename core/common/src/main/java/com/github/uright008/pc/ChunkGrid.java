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

    private final ChunkAccess[][] chunks;
    private final LevelChunkSection[][][] sections;
    private final int minSectionX;
    private final int minSectionZ;
    private final int sizeX;
    private final int sizeZ;

    public ChunkGrid(ServerLevel level, double centerX, double centerZ, float radius) {
        int scx = SectionPos.blockToSectionCoord((int) Math.floor(centerX));
        int scz = SectionPos.blockToSectionCoord((int) Math.floor(centerZ));
        int range = (int) Math.ceil(radius / 16.0) + 1;
        this.sizeX = range * 2 + 1;
        this.sizeZ = range * 2 + 1;
        this.minSectionX = scx - range;
        this.minSectionZ = scz - range;
        this.chunks = new ChunkAccess[sizeX][sizeZ];
        this.sections = new LevelChunkSection[sizeX][sizeZ][];

        ChunkSafeAccessor scs = (ChunkSafeAccessor) level.getChunkSource();
        for (int dx = 0; dx < sizeX; dx++) {
            for (int dz = 0; dz < sizeZ; dz++) {
                ChunkAccess chunk = scs.parallelCore$getChunkSafe(minSectionX + dx, minSectionZ + dz);
                this.chunks[dx][dz] = chunk;
                if (chunk != null) {
                    this.sections[dx][dz] = new LevelChunkSection[chunk.getSectionsCount()];
                }
            }
        }
    }

    @Nullable
    public ChunkAccess getChunk(int sectionX, int sectionZ) {
        int gx = sectionX - minSectionX;
        int gz = sectionZ - minSectionZ;
        if (gx < 0 || gx >= sizeX || gz < 0 || gz >= sizeZ) return null;
        return chunks[gx][gz];
    }

    public BlockState getBlockState(int sectionX, int sectionZ, int blockY, int localX, int localY, int localZ) {
        int gx = sectionX - minSectionX;
        int gz = sectionZ - minSectionZ;
        if (gx < 0 || gx >= sizeX || gz < 0 || gz >= sizeZ) return Blocks.AIR.defaultBlockState();

        ChunkAccess chunk = chunks[gx][gz];
        if (chunk == null) return Blocks.AIR.defaultBlockState();

        int secIdx = chunk.getSectionIndex(blockY);
        if (secIdx < 0 || secIdx >= chunk.getSectionsCount()) return Blocks.AIR.defaultBlockState();

        LevelChunkSection[] chunkSections = sections[gx][gz];
        LevelChunkSection section = chunkSections[secIdx];
        if (section == null) {
            section = chunk.getSection(secIdx);
            chunkSections[secIdx] = section;
        }
        return section != null ? section.getBlockState(localX, localY, localZ) : Blocks.AIR.defaultBlockState();
    }

    @Nullable
    public LevelChunkSection getSection(int sectionX, int sectionZ, int blockY) {
        int gx = sectionX - minSectionX;
        int gz = sectionZ - minSectionZ;
        if (gx < 0 || gx >= sizeX || gz < 0 || gz >= sizeZ) return null;

        ChunkAccess chunk = chunks[gx][gz];
        if (chunk == null) return null;

        int secIdx = chunk.getSectionIndex(blockY);
        if (secIdx < 0 || secIdx >= chunk.getSectionsCount()) return null;

        LevelChunkSection[] chunkSections = sections[gx][gz];
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

        ChunkAccess chunk = chunks[gx][gz];
        if (chunk == null) {
            out.chunk = null;
            out.section = null;
            return;
        }

        int secIdx = chunk.getSectionIndex(blockY);
        if (secIdx < 0 || secIdx >= chunk.getSectionsCount()) {
            out.chunk = chunk;
            out.section = null;
            return;
        }

        LevelChunkSection[] chunkSections = sections[gx][gz];
        LevelChunkSection section = chunkSections[secIdx];
        if (section == null) {
            section = chunk.getSection(secIdx);
            chunkSections[secIdx] = section;
        }
        out.chunk = chunk;
        out.section = section;
    }
}
