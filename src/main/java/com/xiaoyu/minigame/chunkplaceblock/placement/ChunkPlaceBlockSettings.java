package com.xiaoyu.minigame.chunkplaceblock.placement;

import com.xiaoyu.minigame.chunkplaceblock.config.ChunkPlaceBlockConfig;

public record ChunkPlaceBlockSettings(
        boolean enabled,
        int radius,
        boolean syncBlockEntityData,
        boolean applyToFutureChunkLoads,
        boolean destroySamePositionOnBreak,
        int placementDelayTicks,
        int blockEntitySyncDelayTicks,
        int chunksPerTick,
        int maxMsPerTick,
        boolean loadedChunksOnly,
        boolean allowChunkGeneration,
        boolean allowConcurrentTasks
) {
    public static ChunkPlaceBlockSettings fromConfig() {
        return new ChunkPlaceBlockSettings(
                ChunkPlaceBlockConfig.ENABLED.getAsBoolean(),
                ChunkPlaceBlockConfig.RADIUS.getAsInt(),
                ChunkPlaceBlockConfig.SYNC_BLOCK_ENTITY_DATA.getAsBoolean(),
                ChunkPlaceBlockConfig.APPLY_TO_FUTURE_CHUNK_LOADS.getAsBoolean(),
                ChunkPlaceBlockConfig.DESTROY_SAME_POSITION_ON_BREAK.getAsBoolean(),
                ChunkPlaceBlockConfig.PLACEMENT_DELAY_TICKS.getAsInt(),
                ChunkPlaceBlockConfig.BLOCK_ENTITY_SYNC_DELAY_TICKS.getAsInt(),
                ChunkPlaceBlockConfig.CHUNKS_PER_TICK.getAsInt(),
                ChunkPlaceBlockConfig.MAX_MS_PER_TICK.getAsInt(),
                ChunkPlaceBlockConfig.LOADED_CHUNKS_ONLY.getAsBoolean(),
                ChunkPlaceBlockConfig.ALLOW_CHUNK_GENERATION.getAsBoolean(),
                ChunkPlaceBlockConfig.ALLOW_CONCURRENT_TASKS.getAsBoolean()
        );
    }
}
