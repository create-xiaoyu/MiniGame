package com.xiaoyu.minigame.gamefeature.sameblockbreak.destruction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

public final class ChunkQueueBuilder {
    private ChunkQueueBuilder() {
    }

    public static long[] build(BlockPos center, int radius) {
        return build(center, radius, center);
    }

    public static long[] build(BlockPos center, int radius, BlockPos priorityCenter) {
        int priorityChunkX = SectionPos.blockToSectionCoord(priorityCenter.getX());
        int priorityChunkZ = SectionPos.blockToSectionCoord(priorityCenter.getZ());
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

        chunks.sort(Comparator.comparingLong(key -> distanceSquared(key, priorityChunkX, priorityChunkZ)));

        long[] queue = new long[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            queue[i] = chunks.get(i);
        }
        return queue;
    }

    public static long[] buildLoaded(ServerLevel level, BlockPos center, int radius, BlockPos priorityCenter) {
        int centerChunkX = SectionPos.blockToSectionCoord(center.getX());
        int centerChunkZ = SectionPos.blockToSectionCoord(center.getZ());
        int priorityChunkX = SectionPos.blockToSectionCoord(priorityCenter.getX());
        int priorityChunkZ = SectionPos.blockToSectionCoord(priorityCenter.getZ());
        int minChunkX = SectionPos.blockToSectionCoord(center.getX() - radius);
        int maxChunkX = SectionPos.blockToSectionCoord(center.getX() + radius);
        int minChunkZ = SectionPos.blockToSectionCoord(center.getZ() - radius);
        int maxChunkZ = SectionPos.blockToSectionCoord(center.getZ() + radius);

        ArrayList<Long> chunks = new ArrayList<>();
        HashSet<Long> seen = new HashSet<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (level.getChunkSource().getChunkNow(chunkX, chunkZ) != null) {
                    addChunk(chunks, seen, chunkX, chunkZ);
                }
            }
        }

        if (level.getChunkSource().getChunkNow(centerChunkX, centerChunkZ) != null) {
            addChunk(chunks, seen, centerChunkX, centerChunkZ);
        }

        chunks.sort(Comparator.comparingLong(key -> distanceSquared(key, priorityChunkX, priorityChunkZ)));

        long[] queue = new long[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            queue[i] = chunks.get(i);
        }
        return queue;
    }

    private static void addChunk(ArrayList<Long> chunks, HashSet<Long> seen, int chunkX, int chunkZ) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        if (seen.add(key)) {
            chunks.add(key);
        }
    }

    private static long distanceSquared(long chunkKey, int centerChunkX, int centerChunkZ) {
        long dx = (long) ChunkPos.getX(chunkKey) - centerChunkX;
        long dz = (long) ChunkPos.getZ(chunkKey) - centerChunkZ;
        return dx * dx + dz * dz;
    }
}
