package com.xiaoyu.minigame.chunkplaceblock.placement;

import java.util.ArrayList;
import java.util.Comparator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

public final class ChunkPositionQueueBuilder {
    private ChunkPositionQueueBuilder() {
    }

    public static long[] build(BlockPos center, int radius) {
        int centerChunkX = SectionPos.blockToSectionCoord(center.getX());
        int centerChunkZ = SectionPos.blockToSectionCoord(center.getZ());
        int minChunkX = SectionPos.blockToSectionCoord(center.getX() - radius);
        int maxChunkX = SectionPos.blockToSectionCoord(center.getX() + radius);
        int minChunkZ = SectionPos.blockToSectionCoord(center.getZ() - radius);
        int maxChunkZ = SectionPos.blockToSectionCoord(center.getZ() + radius);

        long count = (long) (maxChunkX - minChunkX + 1) * (long) (maxChunkZ - minChunkZ + 1);
        ArrayList<Long> chunks = new ArrayList<>((int) Math.min(count, Integer.MAX_VALUE));
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                chunks.add(ChunkPos.pack(chunkX, chunkZ));
            }
        }

        chunks.sort(Comparator.comparingLong(key -> distanceSquared(key, centerChunkX, centerChunkZ)));

        long[] queue = new long[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            queue[i] = chunks.get(i);
        }
        return queue;
    }

    private static long distanceSquared(long chunkKey, int centerChunkX, int centerChunkZ) {
        long dx = (long) ChunkPos.getX(chunkKey) - centerChunkX;
        long dz = (long) ChunkPos.getZ(chunkKey) - centerChunkZ;
        return dx * dx + dz * dz;
    }
}
