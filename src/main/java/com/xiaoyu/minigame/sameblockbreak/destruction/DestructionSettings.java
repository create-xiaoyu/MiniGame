package com.xiaoyu.minigame.sameblockbreak.destruction;

import com.xiaoyu.minigame.sameblockbreak.config.SameBlockBreakConfig;

public record DestructionSettings(
        boolean enabled,
        int radius,
        boolean matchBlockType,
        int dropRadius,
        int maxNaturalBreakBlocks,
        boolean cleanupUnsupportedNeighbors,
        boolean rememberBrokenBlocksForever,
        int chunksPerTick,
        int maxMsPerTick,
        int blocksPerTick,
        int blocksPerChunkStep,
        boolean loadedChunksOnly,
        boolean allowChunkGeneration,
        boolean allowConcurrentTasks
) {
    public static DestructionSettings fromConfig() {
        return new DestructionSettings(
                SameBlockBreakConfig.ENABLED.getAsBoolean(),
                SameBlockBreakConfig.RADIUS.getAsInt(),
                SameBlockBreakConfig.MATCH_BLOCK_TYPE.getAsBoolean(),
                SameBlockBreakConfig.DROP_RADIUS.getAsInt(),
                SameBlockBreakConfig.MAX_NATURAL_BREAK_BLOCKS.getAsInt(),
                SameBlockBreakConfig.CLEANUP_UNSUPPORTED_NEIGHBORS.getAsBoolean(),
                SameBlockBreakConfig.REMEMBER_BROKEN_BLOCKS_FOREVER.getAsBoolean(),
                SameBlockBreakConfig.CHUNKS_PER_TICK.getAsInt(),
                SameBlockBreakConfig.MAX_MS_PER_TICK.getAsInt(),
                SameBlockBreakConfig.BLOCKS_PER_TICK.getAsInt(),
                SameBlockBreakConfig.BLOCKS_PER_CHUNK_STEP.getAsInt(),
                SameBlockBreakConfig.LOADED_CHUNKS_ONLY.getAsBoolean(),
                SameBlockBreakConfig.ALLOW_CHUNK_GENERATION.getAsBoolean(),
                SameBlockBreakConfig.ALLOW_CONCURRENT_TASKS.getAsBoolean()
        );
    }
}
