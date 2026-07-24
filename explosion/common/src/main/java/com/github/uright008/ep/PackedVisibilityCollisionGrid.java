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
        this.cellHeads = new int[strideZ * (maxZ - minZ + 1)];
        this.cellTails = new int[cellHeads.length];
        this.nextBoxes = new int[cellHeads.length];
        this.boxCoordinates = new double[cellHeads.length * 6];
        Arrays.fill(cellHeads, -1);
        Arrays.fill(cellTails, -1);
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
        for (int z = fromZ; z <= toZ; z++) {
            for (int y = fromY; y <= toY; y++) {
                for (int x = fromX; x <= toX; x++) {
                    addToCell(x, y, z, minBoxX, minBoxY, minBoxZ, maxBoxX, maxBoxY, maxBoxZ);
                }
            }
        }
    }

    private void addToCell(int x, int y, int z, double minBoxX, double minBoxY, double minBoxZ,
                           double maxBoxX, double maxBoxY, double maxBoxZ) {
        if (boxCount == nextBoxes.length) {
            nextBoxes = Arrays.copyOf(nextBoxes, boxCount * 2);
            boxCoordinates = Arrays.copyOf(boxCoordinates, boxCount * 12);
        }
        int box = boxCount++;
        int cell = cellIndex(x, y, z);
        int previous = cellTails[cell];
        if (previous == -1) {
            cellHeads[cell] = box;
        } else {
            nextBoxes[previous] = box;
        }
        cellTails[cell] = box;
        nextBoxes[box] = -1;
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
