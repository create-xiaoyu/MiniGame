package com.xiaoyu.minigame.gamefeature.common.world;

import java.util.Optional;
import java.util.concurrent.CompletionException;

import com.xiaoyu.minigame.MiniGame;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

public final class ChunkLoading {
    private ChunkLoading() {
    }

    public static LevelChunk getProcessableChunk(
            ServerLevel level,
            int chunkX,
            int chunkZ,
            boolean loadedChunksOnly,
            boolean allowChunkGeneration
    ) {
        LevelChunk loadedChunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (loadedChunk != null) {
            return loadedChunk;
        }

        if (loadedChunksOnly) {
            return null;
        }

        if (allowChunkGeneration) {
            return level.getChunk(chunkX, chunkZ);
        }

        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        if (!hasSavedChunk(level, chunkPos)) {
            return null;
        }

        return level.getChunk(chunkX, chunkZ);
    }

    private static boolean hasSavedChunk(ServerLevel level, ChunkPos chunkPos) {
        try {
            Optional<CompoundTag> tag = level.getChunkSource().chunkMap.read(chunkPos).join();
            return tag.isPresent();
        } catch (CompletionException exception) {
            MiniGame.LOGGER.warn("Failed to check saved chunk {},{}", chunkPos.x(), chunkPos.z(), exception);
            return false;
        }
    }
}
