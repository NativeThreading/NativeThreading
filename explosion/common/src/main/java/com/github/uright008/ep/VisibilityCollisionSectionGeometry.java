package com.github.uright008.ep;

import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;

public final class VisibilityCollisionSectionGeometry {
    private final boolean contextFree;
    private final boolean onlyAir;
    private final double[] coordinates;
    private final int[] origins;

    private VisibilityCollisionSectionGeometry(boolean contextFree, double[] coordinates, int[] origins,
                                                boolean onlyAir) {
        this.contextFree = contextFree;
        this.coordinates = coordinates;
        this.origins = origins;
        this.onlyAir = onlyAir;
    }

    private VisibilityCollisionSectionGeometry(boolean contextFree, double[] coordinates, int[] origins) {
        this(contextFree, coordinates, origins, coordinates.length == 0);
    }

    static VisibilityCollisionSectionGeometry of(boolean contextFree, boolean onlyAir,
                                                  double[] coordinates, int[] origins) {
        return new VisibilityCollisionSectionGeometry(contextFree, coordinates, origins, onlyAir);
    }

    public static VisibilityCollisionSectionGeometry capture(LevelChunk chunk, int sectionIndex) {
        LevelChunkSection section = chunk.getSection(sectionIndex);
        int originX = chunk.getPos().getMinBlockX();
        int originY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sectionIndex));
        int originZ = chunk.getPos().getMinBlockZ();
        DoubleCoordinates boxes = new DoubleCoordinates();

        for (int localY = 0; localY < 16; localY++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    BlockState state = section.getBlockState(localX, localY, localZ);
                    if (state.isAir()) {
                        continue;
                    }
                    VisibilityCollisionSnapshot.StaticGeometry geometry =
                            VisibilityCollisionSnapshot.staticGeometry(state);
                    if (geometry == null) {
                        return new VisibilityCollisionSectionGeometry(false, new double[0], new int[0], false);
                    }
                    geometry.addTo(boxes, originX + localX, originY + localY, originZ + localZ);
                }
            }
        }
        return new VisibilityCollisionSectionGeometry(true, boxes.toArray(), boxes.origins(), boxes.isEmpty());
    }

    public boolean isContextFree() {
        return contextFree;
    }

    public boolean isOnlyAir() {
        return onlyAir;
    }

    public void addTo(PackedVisibilityCollisionGrid grid, int minX, int minY, int minZ,
                      int maxX, int maxY, int maxZ) {
        int originIdx = 0;
        for (int index = 0; index < coordinates.length; index += 6, originIdx += 3) {
            int originX = origins[originIdx];
            int originY = origins[originIdx + 1];
            int originZ = origins[originIdx + 2];
            if (originX < minX || originX > maxX || originY < minY || originY > maxY
                    || originZ < minZ || originZ > maxZ) {
                continue;
            }
            grid.add(coordinates[index], coordinates[index + 1], coordinates[index + 2],
                    coordinates[index + 3], coordinates[index + 4], coordinates[index + 5]);
        }
    }

    static final class DoubleCoordinates {
        private double[] values = new double[24];
        private int[] origins = new int[12];
        private int size;

        void add(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            add(minX, minY, minZ, maxX, maxY, maxZ, 0, 0, 0);
        }

        void add(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                 int originX, int originY, int originZ) {
            if (size == values.length) {
                double[] expanded = new double[values.length * 2];
                System.arraycopy(values, 0, expanded, 0, values.length);
                values = expanded;
                int[] expandedOrigins = new int[origins.length * 2];
                System.arraycopy(origins, 0, expandedOrigins, 0, origins.length);
                origins = expandedOrigins;
            }
            values[size++] = minX;
            values[size++] = minY;
            values[size++] = minZ;
            values[size++] = maxX;
            values[size++] = maxY;
            values[size++] = maxZ;
            int originIndex = size / 2 - 3;
            origins[originIndex] = originX;
            origins[originIndex + 1] = originY;
            origins[originIndex + 2] = originZ;
        }

        double[] toArray() {
            double[] result = new double[size];
            System.arraycopy(values, 0, result, 0, size);
            return result;
        }

        int[] origins() {
            int[] result = new int[size / 2];
            System.arraycopy(origins, 0, result, 0, result.length);
            return result;
        }

        boolean isEmpty() {
            return size == 0;
        }
    }
}
