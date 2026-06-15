package com.xiaoyu.minigame.gamefeature.chunkplaceblock.config;

import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class ChunkPlaceBlockConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue SYNC_BLOCK_PLACEMENTS;
    public static final ModConfigSpec.BooleanValue SYNC_MULTI_BLOCK_PLACEMENTS;
    public static final ModConfigSpec.BooleanValue SYNC_BUCKET_FLUID_PLACEMENTS;
    public static final ModConfigSpec.BooleanValue SYNC_LIQUID_CONTAINER_FLUID_PLACEMENTS;
    public static final ModConfigSpec.BooleanValue ONLY_PLACE_IN_AIR;
    public static final ModConfigSpec.BooleanValue REQUIRE_ALL_TARGETS_EMPTY_FOR_MULTI_BLOCK;
    public static final ModConfigSpec.BooleanValue CHECK_PLACEMENT_SURVIVAL;
    public static final ModConfigSpec.BooleanValue COPY_BLOCK_ENTITY_DATA;
    public static final ModConfigSpec.BooleanValue PERSIST_PLACEMENT_RULES;
    public static final ModConfigSpec.BooleanValue SYNC_BREAKS;
    public static final ModConfigSpec.BooleanValue SYNC_BREAKS_ONLY_WHEN_SAME_BLOCK_BREAK_DISABLED;
    public static final ModConfigSpec.BooleanValue TRIGGER_PLAYER_BREAKS;
    public static final ModConfigSpec.BooleanValue TRIGGER_LIVING_ENTITY_BREAKS;
    public static final ModConfigSpec.BooleanValue BREAK_ONLY_MATCHING_BLOCK;
    public static final ModConfigSpec.BooleanValue BREAK_REQUIRE_EXACT_STATE;
    public static final ModConfigSpec.BooleanValue BREAK_DROP_RESOURCES;
    public static final ModConfigSpec.BooleanValue SEND_PLACEMENT_MESSAGE;
    public static final ModConfigSpec.BooleanValue SEND_BREAK_MESSAGE;
    public static final ModConfigSpec.BooleanValue DEBUG_LOG_SKIPPED_TARGETS;
    public static final ModConfigSpec.BooleanValue PREVENT_NEIGHBOR_CHUNK_LOADING;
    public static final ModConfigSpec.IntValue PLACEMENT_UPDATE_FLAGS;
    public static final ModConfigSpec.IntValue PLACEMENT_UPDATE_LIMIT;
    public static final ModConfigSpec.IntValue MAX_PLACEMENTS_PER_TRIGGER;
    public static final ModConfigSpec.IntValue MAX_PERSISTENT_CHUNKS_PER_TICK;
    public static final ModConfigSpec.IntValue MAX_PERSISTENT_PLACEMENTS_PER_TICK;
    public static final ModConfigSpec.IntValue MAX_PENDING_BUCKET_TICKS;
    public static final ModConfigSpec.IntValue BREAK_UPDATE_FLAGS;
    public static final ModConfigSpec.IntValue BREAK_UPDATE_LIMIT;
    public static final ModConfigSpec.IntValue MAX_BREAKS_PER_TRIGGER;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("general");

        ENABLED = builder
                .comment("Enables chunk-position block placement and break synchronization.")
                .define("enabled", true);

        PERSIST_PLACEMENT_RULES = builder
                .comment(
                        "Stores the latest synchronized block for each local chunk position and applies it to chunks loaded later.",
                        "Rules are removed when synchronized breaking removes a matching block at that local chunk position.",
                        "Disable this if you only want to affect chunks that are loaded at the moment of placement."
                )
                .define("persistPlacementRules", true);

        DEBUG_LOG_SKIPPED_TARGETS = builder
                .comment("Logs individual skipped placement and break targets. This can be noisy in large worlds.")
                .define("debugLogSkippedTargets", false);

        PREVENT_NEIGHBOR_CHUNK_LOADING = builder
                .comment(
                        "Forces mirrored placement and fast mirrored breaking to use local-chunk-only update behavior.",
                        "When enabled, neighbour updates are removed, UPDATE_KNOWN_SHAPE and UPDATE_SKIP_ON_PLACE are forced on, and update limits are treated as 0.",
                        "This protects TPS by preventing mirrored changes from loading adjacent chunks through shape or neighbour updates.",
                        "Disable only if you deliberately want vanilla-like neighbour updates from every mirrored block change."
                )
                .define("preventNeighborChunkLoading", true);

        builder.pop();
        builder.push("placement");

        SYNC_BLOCK_PLACEMENTS = builder
                .comment("Mirrors successful entity block placement events across loaded chunks.")
                .define("syncBlockPlacements", true);

        SYNC_MULTI_BLOCK_PLACEMENTS = builder
                .comment("Mirrors multi-block placement events such as beds and doors.")
                .define("syncMultiBlockPlacements", true);

        SYNC_BUCKET_FLUID_PLACEMENTS = builder
                .comment("Mirrors successful bucket fluid placements such as water and lava.")
                .define("syncBucketFluidPlacements", true);

        SYNC_LIQUID_CONTAINER_FLUID_PLACEMENTS = builder
                .comment(
                        "Allows bucket placements into liquid containers, such as waterlogging blocks, to be mirrored.",
                        "Default is false because the target chunks usually contain air, not the same container block."
                )
                .define("syncLiquidContainerFluidPlacements", false);

        ONLY_PLACE_IN_AIR = builder
                .comment("Only mirrors placement into air blocks. This protects existing blocks in other chunks.")
                .define("onlyPlaceInAir", true);

        REQUIRE_ALL_TARGETS_EMPTY_FOR_MULTI_BLOCK = builder
                .comment("For multi-block placements, skips a target chunk unless all mirrored target positions are placeable.")
                .define("requireAllTargetsEmptyForMultiBlock", true);

        CHECK_PLACEMENT_SURVIVAL = builder
                .comment("Checks BlockState#canSurvive before mirrored placement. Disable to copy the exact state even if unsupported.")
                .define("checkPlacementSurvival", false);

        COPY_BLOCK_ENTITY_DATA = builder
                .comment("Copies placed block entity NBT, data components, and NeoForge persistent data to mirrored block entities.")
                .define("copyBlockEntityData", true);

        PLACEMENT_UPDATE_FLAGS = builder
                .comment(
                        "Update flags used for mirrored placement.",
                        "Default = UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE | UPDATE_SKIP_ON_PLACE.",
                        "This keeps client sync while avoiding neighbour shape updates that can touch or load adjacent chunks."
                )
                .defineInRange("placementUpdateFlags", Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE, 0, 2047);

        PLACEMENT_UPDATE_LIMIT = builder
                .comment("Neighbor update limit used for mirrored placement.")
                .defineInRange("placementUpdateLimit", 0, 0, 512);

        MAX_PLACEMENTS_PER_TRIGGER = builder
                .comment("Maximum mirrored block placements per placement trigger. 0 means unlimited.")
                .defineInRange("maxPlacementsPerTrigger", 0, 0, Integer.MAX_VALUE);

        MAX_PENDING_BUCKET_TICKS = builder
                .comment("Maximum ticks to wait for a predicted bucket placement to appear before discarding it.")
                .defineInRange("maxPendingBucketTicks", 5, 1, 200);

        builder.pop();
        builder.push("persistentRules");

        MAX_PERSISTENT_CHUNKS_PER_TICK = builder
                .comment("Maximum newly loaded chunks processed for persistent placement rules per level tick. 0 means unlimited.")
                .defineInRange("maxPersistentChunksPerTick", 16, 0, Integer.MAX_VALUE);

        MAX_PERSISTENT_PLACEMENTS_PER_TICK = builder
                .comment("Maximum block placements caused by persistent placement rules per level tick. 0 means unlimited.")
                .defineInRange("maxPersistentPlacementsPerTick", 1024, 0, Integer.MAX_VALUE);

        builder.pop();
        builder.push("breaking");

        SYNC_BREAKS = builder
                .comment("Mirrors block breaking across loaded chunks at the same local chunk position.")
                .define("syncBreaks", true);

        SYNC_BREAKS_ONLY_WHEN_SAME_BLOCK_BREAK_DISABLED = builder
                .comment("Skips mirrored breaking while sameblockbreak is enabled, so the two features do not both react to the same break.")
                .define("syncBreaksOnlyWhenSameBlockBreakDisabled", true);

        TRIGGER_PLAYER_BREAKS = builder
                .comment("Starts mirrored breaking when a player breaks a block.")
                .define("triggerPlayerBreaks", true);

        TRIGGER_LIVING_ENTITY_BREAKS = builder
                .comment("Starts mirrored breaking when living entities such as withers or ender dragons destroy blocks.")
                .define("triggerLivingEntityBreaks", true);

        BREAK_ONLY_MATCHING_BLOCK = builder
                .comment("Only breaks target positions whose block matches the source broken block.")
                .define("breakOnlyMatchingBlock", true);

        BREAK_REQUIRE_EXACT_STATE = builder
                .comment("When breakOnlyMatchingBlock is enabled, require the complete BlockState to match, not just the block type.")
                .define("breakRequireExactState", false);

        BREAK_DROP_RESOURCES = builder
                .comment("Allows mirrored breaks to drop resources. Default false prevents large item duplication.")
                .define("breakDropResources", false);

        BREAK_UPDATE_FLAGS = builder
                .comment(
                        "Update flags used for mirrored breaks when breakDropResources is false.",
                        "Default = UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE | UPDATE_SKIP_ON_PLACE.",
                        "Keeping this conservative prevents synced breaking from loading neighbour chunks."
                )
                .defineInRange("breakUpdateFlags", Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE, 0, 2047);

        BREAK_UPDATE_LIMIT = builder
                .comment("Neighbor update limit used by mirrored breaking. Treated as 0 when preventNeighborChunkLoading is enabled.")
                .defineInRange("breakUpdateLimit", 0, 0, 512);

        MAX_BREAKS_PER_TRIGGER = builder
                .comment("Maximum mirrored block breaks per break trigger. 0 means unlimited.")
                .defineInRange("maxBreaksPerTrigger", 0, 0, Integer.MAX_VALUE);

        builder.pop();
        builder.push("messages");

        SEND_PLACEMENT_MESSAGE = builder
                .comment("Sends a localized message to the triggering player after mirrored placement.")
                .define("sendPlacementMessage", false);

        SEND_BREAK_MESSAGE = builder
                .comment("Sends a localized message to the triggering player after mirrored breaking.")
                .define("sendBreakMessage", false);

        builder.pop();

        SPEC = builder.build();
    }

    private ChunkPlaceBlockConfig() {
    }
}
