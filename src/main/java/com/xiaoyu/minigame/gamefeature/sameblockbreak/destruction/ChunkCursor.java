package com.xiaoyu.minigame.gamefeature.sameblockbreak.destruction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class ChunkCursor {
    private final int chunkX;
    private final int chunkZ;

    private int sectionIndex;
    private int localX;
    private int localY;
    private int localZ;
    private boolean complete;

    public ChunkCursor(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public BlockPos.MutableBlockPos next(LevelChunk chunk, BlockPos.MutableBlockPos mutablePos) {
        if (complete) {
            return null;
        }

        LevelChunkSection[] sections = chunk.getSections();
        while (sectionIndex < sections.length) {
            LevelChunkSection section = sections[sectionIndex];
            if (section.hasOnlyAir()) {
                advanceSection();
                continue;
            }

            int worldX = (chunkX << 4) + localX;
            int worldY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sectionIndex), localY);
            int worldZ = (chunkZ << 4) + localZ;
            mutablePos.set(worldX, worldY, worldZ);
            advanceBlock();
            return mutablePos;
        }

        complete = true;
        return null;
    }

    private void advanceBlock() {
        localX++;
        if (localX >= 16) {
            localX = 0;
            localZ++;
            if (localZ >= 16) {
                localZ = 0;
                localY++;
                if (localY >= 16) {
                    advanceSection();
                }
            }
        }
    }

    public boolean isComplete() {
        return complete;
    }

    private void advanceSection() {
        localX = 0;
        localY = 0;
        localZ = 0;
        sectionIndex++;
    }
}
