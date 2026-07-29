package com.github.uright008.ep;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class VisibilityCollisionSnapshot {
    private static volatile StaticGeometry[] staticGeometryByStateId = new StaticGeometry[0];
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;
    private final int strideY;
    private final int strideZ;
    private final int[] cellHeads;
    private final int[] nextBoxes;
    private final double[] boxCoordinates;

    private VisibilityCollisionSnapshot(PackedVisibilityCollisionGrid builder) {
        this.minX = builder.minX;
        this.minY = builder.minY;
        this.minZ = builder.minZ;
        this.maxX = builder.maxX;
        this.maxY = builder.maxY;
        this.maxZ = builder.maxZ;
        this.strideY = builder.strideY;
        this.strideZ = builder.strideZ;
        this.cellHeads = builder.cellHeads;
        this.nextBoxes = builder.nextBoxes;
        this.boxCoordinates = builder.boxCoordinates;
    }

    private VisibilityCollisionSnapshot() {
        this.minX = 0;
        this.minY = 0;
        this.minZ = 0;
        this.maxX = -1;
        this.maxY = -1;
        this.maxZ = -1;
        this.strideY = 0;
        this.strideZ = 0;
        this.cellHeads = new int[0];
        this.nextBoxes = new int[0];
        this.boxCoordinates = new double[0];
    }

    public static @Nullable VisibilityCollisionSnapshot capture(ServerLevel level, Vec3 center, float doubleRadius) {
        int minX = Mth.floor(center.x - doubleRadius);
        int maxX = Mth.floor(center.x + doubleRadius);
        int minY = Mth.floor(center.y - doubleRadius);
        int maxY = Mth.floor(center.y + doubleRadius);
        int minZ = Mth.floor(center.z - doubleRadius);
        int maxZ = Mth.floor(center.z + doubleRadius);
        PackedVisibilityCollisionGrid builder = new PackedVisibilityCollisionGrid(minX, minY, minZ, maxX, maxY, maxZ);
        int minSectionX = SectionPos.blockToSectionCoord(minX);
        int maxSectionX = SectionPos.blockToSectionCoord(maxX);
        int minSectionY = SectionPos.blockToSectionCoord(minY);
        int maxSectionY = SectionPos.blockToSectionCoord(maxY);
        int minSectionZ = SectionPos.blockToSectionCoord(minZ);
        int maxSectionZ = SectionPos.blockToSectionCoord(maxZ);
        for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
            for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(sectionX, sectionZ);
                if (chunk == null) {
                    continue;
                }
                VisibilityCollisionChunkCache cache = (VisibilityCollisionChunkCache) chunk;
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    int sectionIndex = chunk.getSectionIndex(SectionPos.sectionToBlockCoord(sectionY));
                    if (sectionIndex < 0 || sectionIndex >= chunk.getSectionsCount()) {
                        continue;
                    }
                    VisibilityCollisionSectionGeometry geometry = cache.explosion$getVisibilityCollisionSection(sectionIndex);
                    if (geometry == null || !geometry.isContextFree()) {
                        return null;
                    }
                    if (geometry.isOnlyAir()) {
                        continue;
                    }
                    geometry.addTo(builder, minX, minY, minZ, maxX, maxY, maxZ);
                }
            }
        }
        return new VisibilityCollisionSnapshot(builder);
    }

    public static void initializeStaticGeometryTable() {
        int maxStateId = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                maxStateId = Math.max(maxStateId, Block.getId(state));
            }
        }
        StaticGeometry[] table = new StaticGeometry[maxStateId + 1];
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                if (!isContextFree(state)) {
                    continue;
                }
                DoubleCoordinates boxes = new DoubleCoordinates();
                state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).forAllBoxes(boxes);
                table[Block.getId(state)] = new StaticGeometry(boxes.toArray());
            }
        }
        staticGeometryByStateId = table;
    }

    static @Nullable StaticGeometry staticGeometry(BlockState state) {
        StaticGeometry[] table = staticGeometryByStateId;
        int stateId = Block.getId(state);
        return stateId >= 0 && stateId < table.length ? table[stateId] : null;
    }

    static boolean hasStaticGeometry(BlockState state) {
        return staticGeometry(state) != null;
    }

    public static boolean isContextFree(BlockState state) {
        boolean isSourceLiquid = state.getBlock() instanceof LiquidBlock && state.getFluidState().isSource();
        return isContextFree(state.getBlock().hasDynamicShape(), isSourceLiquid);
    }

    static boolean isContextFree(boolean hasDynamicShape, boolean isSourceLiquid) {
        return !hasDynamicShape && !isSourceLiquid;
    }

    static final class StaticGeometry {
        private final double[] coordinates;

        StaticGeometry(double[] coordinates) {
            this.coordinates = coordinates;
        }

        void addTo(VisibilityCollisionSectionGeometry.DoubleCoordinates target, int x, int y, int z) {
            for (int index = 0; index < coordinates.length; index += 6) {
                target.add(x + coordinates[index], y + coordinates[index + 1], z + coordinates[index + 2],
                        x + coordinates[index + 3], y + coordinates[index + 4], z + coordinates[index + 5],
                        x, y, z);
            }
        }
    }

    private static final class DoubleCoordinates implements net.minecraft.world.phys.shapes.Shapes.DoubleLineConsumer {
        private double[] values = new double[24];
        private int size;

        @Override
        public void consume(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            if (size == values.length) {
                double[] expanded = new double[values.length * 2];
                System.arraycopy(values, 0, expanded, 0, values.length);
                values = expanded;
            }
            values[size++] = minX;
            values[size++] = minY;
            values[size++] = minZ;
            values[size++] = maxX;
            values[size++] = maxY;
            values[size++] = maxZ;
        }

        private double[] toArray() {
            double[] result = new double[size];
            System.arraycopy(values, 0, result, 0, size);
            return result;
        }
    }

    public static VisibilityCollisionSnapshot of(List<CollisionBox> boxes) {
        if (boxes.isEmpty()) {
            return new VisibilityCollisionSnapshot();
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (CollisionBox box : boxes) {
            minX = Math.min(minX, Mth.floor(box.minX));
            minY = Math.min(minY, Mth.floor(box.minY));
            minZ = Math.min(minZ, Mth.floor(box.minZ));
            maxX = Math.max(maxX, Mth.floor(box.maxX));
            maxY = Math.max(maxY, Mth.floor(box.maxY));
            maxZ = Math.max(maxZ, Mth.floor(box.maxZ));
        }
        PackedVisibilityCollisionGrid builder = new PackedVisibilityCollisionGrid(minX, minY, minZ, maxX, maxY, maxZ);
        for (CollisionBox box : boxes) {
            builder.add(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
        }
        return new VisibilityCollisionSnapshot(builder);
    }

    public boolean blocks(RaySegment segment) {
        double deltaX = segment.toX - segment.fromX;
        double deltaY = segment.toY - segment.fromY;
        double deltaZ = segment.toZ - segment.fromZ;
        if (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ < 1.0E-7) {
            return false;
        }

        double fromX = Mth.lerp(-1.0E-7, segment.fromX, segment.toX);
        double fromY = Mth.lerp(-1.0E-7, segment.fromY, segment.toY);
        double fromZ = Mth.lerp(-1.0E-7, segment.fromZ, segment.toZ);
        double toX = Mth.lerp(-1.0E-7, segment.toX, segment.fromX);
        double toY = Mth.lerp(-1.0E-7, segment.toY, segment.fromY);
        double toZ = Mth.lerp(-1.0E-7, segment.toZ, segment.fromZ);
        double directionX = toX - fromX;
        double directionY = toY - fromY;
        double directionZ = toZ - fromZ;
        int stepX = Mth.sign(directionX);
        int stepY = Mth.sign(directionY);
        int stepZ = Mth.sign(directionZ);
        double deltaCellX = stepX == 0 ? Double.MAX_VALUE : stepX / directionX;
        double deltaCellY = stepY == 0 ? Double.MAX_VALUE : stepY / directionY;
        double deltaCellZ = stepZ == 0 ? Double.MAX_VALUE : stepZ / directionZ;
        double nextCellX = deltaCellX * (stepX > 0 ? 1.0 - Mth.frac(fromX) : Mth.frac(fromX));
        double nextCellY = deltaCellY * (stepY > 0 ? 1.0 - Mth.frac(fromY) : Mth.frac(fromY));
        double nextCellZ = deltaCellZ * (stepZ > 0 ? 1.0 - Mth.frac(fromZ) : Mth.frac(fromZ));
        int cellX = Mth.floor(fromX);
        int cellY = Mth.floor(fromY);
        int cellZ = Mth.floor(fromZ);

        while (true) {
            int box = cellHead(cellX, cellY, cellZ);
            while (box != 0) {
                if (intersects(box, segment)) {
                    return true;
                }
                box = nextBoxes[box];
            }
            if (nextCellX > 1.0 && nextCellY > 1.0 && nextCellZ > 1.0) {
                return false;
            }
            if (nextCellX < nextCellY) {
                if (nextCellX < nextCellZ) {
                    cellX += stepX;
                    nextCellX += deltaCellX;
                } else {
                    cellZ += stepZ;
                    nextCellZ += deltaCellZ;
                }
            } else if (nextCellY < nextCellZ) {
                cellY += stepY;
                nextCellY += deltaCellY;
            } else {
                cellZ += stepZ;
                nextCellZ += deltaCellZ;
            }
        }
    }

    private int cellHead(int x, int y, int z) {
        if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) {
            return 0;
        }
        return cellHeads[(x - minX) + (y - minY) * strideY + (z - minZ) * strideZ];
    }

    public List<double[]> getBoxesForCell(int x, int y, int z) {
        int box = cellHead(x, y, z);
        if (box == 0) return List.of();
        var result = new java.util.ArrayList<double[]>();
        while (box != 0) {
            int c = box * 6;
            result.add(new double[] {
                    boxCoordinates[c], boxCoordinates[c + 1], boxCoordinates[c + 2],
                    boxCoordinates[c + 3], boxCoordinates[c + 4], boxCoordinates[c + 5]
            });
            box = nextBoxes[box];
        }
        return result;
    }

    private boolean intersects(int box, RaySegment segment) {
        int coordinate = box * 6;
        double deltaX = segment.toX - segment.fromX;
        double deltaY = segment.toY - segment.fromY;
        double deltaZ = segment.toZ - segment.fromZ;
        double fromX = segment.fromX + deltaX * 0.001;
        double fromY = segment.fromY + deltaY * 0.001;
        double fromZ = segment.fromZ + deltaZ * 0.001;
        double directionX = segment.toX - fromX;
        double directionY = segment.toY - fromY;
        double directionZ = segment.toZ - fromZ;
        double minimum = 0.0;
        double maximum = 1.0;

        if (directionX == 0.0) {
            if (fromX < boxCoordinates[coordinate] || fromX > boxCoordinates[coordinate + 3]) return false;
        } else {
            double near = (boxCoordinates[coordinate] - fromX) / directionX;
            double far = (boxCoordinates[coordinate + 3] - fromX) / directionX;
            if (near > far) { double swap = near; near = far; far = swap; }
            minimum = Math.max(minimum, near);
            maximum = Math.min(maximum, far);
            if (minimum > maximum) return false;
        }
        if (directionY == 0.0) {
            if (fromY < boxCoordinates[coordinate + 1] || fromY > boxCoordinates[coordinate + 4]) return false;
        } else {
            double near = (boxCoordinates[coordinate + 1] - fromY) / directionY;
            double far = (boxCoordinates[coordinate + 4] - fromY) / directionY;
            if (near > far) { double swap = near; near = far; far = swap; }
            minimum = Math.max(minimum, near);
            maximum = Math.min(maximum, far);
            if (minimum > maximum) return false;
        }
        if (directionZ == 0.0) {
            return fromZ >= boxCoordinates[coordinate + 2] && fromZ <= boxCoordinates[coordinate + 5];
        }
        double near = (boxCoordinates[coordinate + 2] - fromZ) / directionZ;
        double far = (boxCoordinates[coordinate + 5] - fromZ) / directionZ;
        if (near > far) { double swap = near; near = far; far = swap; }
        return Math.max(minimum, near) <= Math.min(maximum, far);
    }

    public record CollisionBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    }

    public record RaySegment(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
    }

}
