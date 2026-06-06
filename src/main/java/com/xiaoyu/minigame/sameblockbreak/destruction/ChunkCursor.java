package com.xiaoyu.minigame.sameblockbreak.destruction;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

public final class ChunkCursor {
    private final int chunkX;
    private final int chunkZ;
    private final int minY;

    private int localX;
    private int localZ;
    private int y;
    private boolean columnReady;
    private boolean complete;

    public ChunkCursor(int chunkX, int chunkZ, int minY) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minY = minY;
    }

    public BlockPos.MutableBlockPos next(ServerLevel level, BlockPos.MutableBlockPos mutablePos) {
        if (complete) {
            return null;
        }

        while (localX < 16) {
            int worldX = (chunkX << 4) + localX;
            int worldZ = (chunkZ << 4) + localZ;
            if (!columnReady) {
                y = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ) - 1;
                columnReady = true;
                if (y < minY) {
                    advanceColumn();
                    continue;
                }
            }

            mutablePos.set(worldX, y, worldZ);
            y--;
            if (y < minY) {
                advanceColumn();
            }
            return mutablePos;
        }

        complete = true;
        return null;
    }

    public boolean isComplete() {
        return complete;
    }

    private void advanceColumn() {
        columnReady = false;
        localZ++;
        if (localZ >= 16) {
            localZ = 0;
            localX++;
            if (localX >= 16) {
                complete = true;
            }
        }
    }
}
