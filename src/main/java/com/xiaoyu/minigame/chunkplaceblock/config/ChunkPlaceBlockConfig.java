package com.xiaoyu.minigame.chunkplaceblock.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ChunkPlaceBlockConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue RADIUS;
    public static final ModConfigSpec.BooleanValue SYNC_BLOCK_ENTITY_DATA;
    public static final ModConfigSpec.BooleanValue APPLY_TO_FUTURE_CHUNK_LOADS;
    public static final ModConfigSpec.BooleanValue DESTROY_SAME_POSITION_ON_BREAK;
    public static final ModConfigSpec.IntValue PLACEMENT_DELAY_TICKS;
    public static final ModConfigSpec.IntValue BLOCK_ENTITY_SYNC_DELAY_TICKS;
    public static final ModConfigSpec.IntValue CHUNKS_PER_TICK;
    public static final ModConfigSpec.IntValue MAX_MS_PER_TICK;
    public static final ModConfigSpec.BooleanValue LOADED_CHUNKS_ONLY;
    public static final ModConfigSpec.BooleanValue ALLOW_CHUNK_GENERATION;
    public static final ModConfigSpec.BooleanValue ALLOW_CONCURRENT_TASKS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("chunkplaceblock");
        ENABLED = builder
                .comment("Enable copying placed blocks to the same local position in other chunks.")
                .define("enabled", true);

        builder.push("placement");
        RADIUS = builder
                .comment("Block radius around the triggering block. The chunk queue covers this square radius.")
                .defineInRange("radius", 3000, 0, 10000);
        SYNC_BLOCK_ENTITY_DATA = builder
                .comment("When true, block entity data such as chest contents is copied on placement and synced after later changes.")
                .define("syncBlockEntityData", true);
        APPLY_TO_FUTURE_CHUNK_LOADS = builder
                .comment("When true, remembered placements are applied later as matching chunks naturally load or generate.")
                .define("applyToFutureChunkLoads", true);
        DESTROY_SAME_POSITION_ON_BREAK = builder
                .comment("When true, breaking a block destroys matching blocks at the same local chunk position in other chunks. This is automatically disabled while SameBlockBreak is enabled.")
                .define("destroySamePositionOnBreak", false);
        PLACEMENT_DELAY_TICKS = builder
                .comment("Ticks to wait after a placement event before reading block entity data from the source block.")
                .defineInRange("placementDelayTicks", 1, 0, 20);
        BLOCK_ENTITY_SYNC_DELAY_TICKS = builder
                .comment("Ticks to debounce block entity data changes before syncing them to matching chunks.")
                .defineInRange("blockEntitySyncDelayTicks", 1, 0, 20);
        ALLOW_CONCURRENT_TASKS = builder
                .comment("Allow multiple placement and data sync tasks in the same dimension at once.")
                .define("allowConcurrentTasks", true);
        builder.pop();

        builder.push("performance");
        CHUNKS_PER_TICK = builder
                .comment("Maximum chunk entries attempted per server tick by each task.")
                .defineInRange("chunksPerTick", 200, 1, 10000);
        MAX_MS_PER_TICK = builder
                .comment("Maximum main-thread milliseconds spent by each task per tick.")
                .defineInRange("maxMsPerTick", 20, 1, 50);
        LOADED_CHUNKS_ONLY = builder
                .comment("When true, only already loaded chunks are modified.")
                .define("loadedChunksOnly", true);
        ALLOW_CHUNK_GENERATION = builder
                .comment("When true, missing chunks may be synchronously loaded or generated. Keep false for large radii.")
                .define("allowChunkGeneration", false);
        builder.pop();
        builder.pop();

        SPEC = builder.build();
    }

    private ChunkPlaceBlockConfig() {
    }
}
