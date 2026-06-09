package com.xiaoyu.minigame.gamefeature.sameblockbreak.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class SameBlockBreakConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.IntValue RADIUS;
    public static final ModConfigSpec.BooleanValue MATCH_BLOCK_TYPE;
    public static final ModConfigSpec.IntValue DROP_RADIUS;
    public static final ModConfigSpec.IntValue MAX_NATURAL_BREAK_BLOCKS;
    public static final ModConfigSpec.BooleanValue CLEANUP_UNSUPPORTED_NEIGHBORS;
    public static final ModConfigSpec.BooleanValue REMEMBER_BROKEN_BLOCKS_FOREVER;
    public static final ModConfigSpec.IntValue CHUNKS_PER_TICK;
    public static final ModConfigSpec.IntValue MAX_MS_PER_TICK;
    public static final ModConfigSpec.IntValue BLOCKS_PER_TICK;
    public static final ModConfigSpec.IntValue BLOCKS_PER_CHUNK_STEP;
    public static final ModConfigSpec.BooleanValue LOADED_CHUNKS_ONLY;
    public static final ModConfigSpec.BooleanValue ALLOW_CHUNK_GENERATION;
    public static final ModConfigSpec.BooleanValue ALLOW_CONCURRENT_TASKS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("sameblockbreak");
        ENABLED = builder
                .comment("Enable same-block batch destruction after a player breaks a block.")
                .define("enabled", true);

        builder.push("destruction");
        RADIUS = builder
                .comment("Block radius around the triggering block. The chunk queue covers this square radius.")
                .defineInRange("radius", 3000, 0, 10000);
        MATCH_BLOCK_TYPE = builder
                .comment("When true, only blocks with the same Block type are destroyed. When false, all non-air blocks are destroyed.")
                .define("matchBlockType", true);
        DROP_RADIUS = builder
                .comment("Blocks within this X/Z radius use natural breaking and can drop items.")
                .defineInRange("dropRadius", 100, 0, 10000);
        MAX_NATURAL_BREAK_BLOCKS = builder
                .comment("Maximum number of naturally-broken blocks per task. Use -1 for unlimited, 0 for no drops.")
                .defineInRange("maxNaturalBreakBlocks", 3000, -1, Integer.MAX_VALUE);
        CLEANUP_UNSUPPORTED_NEIGHBORS = builder
                .comment("When true, blocks that can no longer survive next to a removed block, such as grass, saplings, torches, and sugar cane, are removed too.")
                .define("cleanupUnsupportedNeighbors", true);
        REMEMBER_BROKEN_BLOCKS_FOREVER = builder
                .comment("When true, each same-block trigger permanently prevents that block type from being generated or placed again in this save.")
                .define("rememberBrokenBlocksForever", true);
        ALLOW_CONCURRENT_TASKS = builder
                .comment("Allow multiple destruction tasks in the same dimension at once.")
                .define("allowConcurrentTasks", true);
        builder.pop();

        builder.push("performance");
        CHUNKS_PER_TICK = builder
                .comment("Maximum chunk entries attempted per server tick.")
                .defineInRange("chunksPerTick", 200, 1, 10000);
        MAX_MS_PER_TICK = builder
                .comment("Maximum main-thread milliseconds spent by each task per tick.")
                .defineInRange("maxMsPerTick", 25, 1, 50);
        BLOCKS_PER_TICK = builder
                .comment("Maximum block positions scanned by each task per tick.")
                .defineInRange("blocksPerTick", 30000, 1, 10_000_000);
        BLOCKS_PER_CHUNK_STEP = builder
                .comment("Maximum block positions scanned from one chunk before yielding.")
                .defineInRange("blocksPerChunkStep", 3000, 1, 1_000_000);
        LOADED_CHUNKS_ONLY = builder
                .comment("When true, only already loaded chunks are scanned.")
                .define("loadedChunksOnly", true);
        ALLOW_CHUNK_GENERATION = builder
                .comment("When true, missing chunks may be synchronously loaded or generated. Keep false for large radii.")
                .define("allowChunkGeneration", false);
        builder.pop();
        builder.pop();

        SPEC = builder.build();
    }

    private SameBlockBreakConfig() {
    }
}
