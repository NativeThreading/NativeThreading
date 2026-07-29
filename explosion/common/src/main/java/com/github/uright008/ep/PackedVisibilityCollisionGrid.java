package com.github.uright008.ep;

import java.util.Arrays;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.shapes.Shapes;

final class PackedVisibilityCollisionGrid implements Shapes.DoubleLineConsumer {
    final int minX;
    final int minY;
    final int minZ;
    final int maxX;
    final int maxY;
    final int maxZ;
    final int strideY;
    final int strideZ;
    final int[] cellHeads;
    private final int[] cellTails;
    int[] nextBoxes;
    double[] boxCoordinates;
    private int boxCount;
    private int originX;
    private int originY;
    private int originZ;

    PackedVisibilityCollisionGrid(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.strideY = maxX - minX + 1;
        this.strideZ = strideY * (maxY - minY + 1);
        int cellCount = strideZ * (maxZ - minZ + 1);
        this.cellHeads = new int[cellCount];
        this.cellTails = new int[cellCount];
        // Pre-allocate based on volume - each block can contribute at most one box
        int initialCapacity = Math.min(cellCount, 4096);
        this.nextBoxes = new int[initialCapacity];
        this.boxCoordinates = new double[initialCapacity * 6];
        // cellHeads and cellTails default to 0, which we use as "empty" sentinel
        // box index 0 is reserved as sentinel, so we start boxCount at 1
        this.boxCount = 1;
    }

    void setOrigin(int x, int y, int z) {
        originX = x;
        originY = y;
        originZ = z;
    }

    @Override
    public void consume(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        add(originX + minX, originY + minY, originZ + minZ,
                originX + maxX, originY + maxY, originZ + maxZ);
    }

    void add(double minBoxX, double minBoxY, double minBoxZ,
             double maxBoxX, double maxBoxY, double maxBoxZ) {
        int fromX = Math.max(minX, Mth.floor(minBoxX));
        int fromY = Math.max(minY, Mth.floor(minBoxY));
        int fromZ = Math.max(minZ, Mth.floor(minBoxZ));
        int toX = Math.min(maxX, Mth.floor(maxBoxX));
        int toY = Math.min(maxY, Mth.floor(maxBoxY));
        int toZ = Math.min(maxZ, Mth.floor(maxBoxZ));
        if (fromX > toX || fromY > toY || fromZ > toZ) return;
        
        int baseIndex = (fromX - minX) + (fromY - minY) * strideY + (fromZ - minZ) * strideZ;
        int strideYDelta = strideY - (toX - fromX + 1);
        int strideZDelta = strideZ - (toY - fromY + 1) * strideY;
        
        for (int z = fromZ; z <= toZ; z++) {
            for (int y = fromY; y <= toY; y++) {
                for (int x = fromX; x <= toX; x++) {
                    addToCell(baseIndex, minBoxX, minBoxY, minBoxZ, maxBoxX, maxBoxY, maxBoxZ);
                    baseIndex++;
                }
                baseIndex += strideYDelta;
            }
            baseIndex += strideZDelta;
        }
    }

    private void addToCell(int cell, double minBoxX, double minBoxY, double minBoxZ,
                           double maxBoxX, double maxBoxY, double maxBoxZ) {
        int box = boxCount++;
        if (box == nextBoxes.length) {
            nextBoxes = Arrays.copyOf(nextBoxes, box * 2);
            boxCoordinates = Arrays.copyOf(boxCoordinates, box * 12);
        }
        int previous = cellTails[cell];
        if (previous == 0) {
            cellHeads[cell] = box;
        } else {
            nextBoxes[previous] = box;
        }
        cellTails[cell] = box;
        nextBoxes[box] = 0;
        int coordinate = box * 6;
        boxCoordinates[coordinate] = minBoxX;
        boxCoordinates[coordinate + 1] = minBoxY;
        boxCoordinates[coordinate + 2] = minBoxZ;
        boxCoordinates[coordinate + 3] = maxBoxX;
        boxCoordinates[coordinate + 4] = maxBoxY;
        boxCoordinates[coordinate + 5] = maxBoxZ;
    }

    private int cellIndex(int x, int y, int z) {
        return (x - minX) + (y - minY) * strideY + (z - minZ) * strideZ;
    }
}
