package com.xiaoyu.minigame.gamefeature.sameblockbreak.config;

import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class SameBlockBreakConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue TRIGGER_PLAYER_BREAKS;
    public static final ModConfigSpec.BooleanValue TRIGGER_LIVING_ENTITY_BREAKS;
    public static final ModConfigSpec.BooleanValue PERSIST_CLEANUP_RULES;
    public static final ModConfigSpec.BooleanValue PREVENT_NON_ENTITY_PLACEMENT_WITH_MIXIN;
    public static final ModConfigSpec.BooleanValue SEND_START_MESSAGE;
    public static final ModConfigSpec.BooleanValue SEND_COMPLETION_MESSAGE;
    public static final ModConfigSpec.IntValue MAX_ACTIVE_TARGETS;
    public static final ModConfigSpec.IntValue MAX_SECTIONS_PER_TICK;
    public static final ModConfigSpec.IntValue MAX_SCANNED_BLOCKS_PER_TICK;
    public static final ModConfigSpec.IntValue MAX_BLOCK_CHANGES_PER_TICK;
    public static final ModConfigSpec.IntValue NORMAL_DESTROY_SECTION_RADIUS;
    public static final ModConfigSpec.IntValue NORMAL_DESTROY_DROP_LIMIT;
    public static final ModConfigSpec.IntValue NORMAL_DESTROY_UPDATE_LIMIT;
    public static final ModConfigSpec.IntValue FAST_REMOVAL_FLAGS;
    public static final ModConfigSpec.IntValue FAST_REMOVAL_UPDATE_LIMIT;
    public static final ModConfigSpec.BooleanValue CLEANUP_UNSUPPORTED_BLOCKS;
    public static final ModConfigSpec.IntValue SUPPORT_CHECKS_PER_TICK;
    public static final ModConfigSpec.IntValue SUPPORT_CASCADE_DEPTH;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BLOCK_DENYLIST;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("general");

        ENABLED = builder
                .comment("Enables same-block cleanup.")
                .define("enabled", true);

        TRIGGER_PLAYER_BREAKS = builder
                .comment("Starts cleanup when a player breaks a block.")
                .define("triggerPlayerBreaks", true);

        TRIGGER_LIVING_ENTITY_BREAKS = builder
                .comment("Starts cleanup when living entities such as withers, ender dragons, or zombies destroy blocks.")
                .define("triggerLivingEntityBreaks", true);

        PERSIST_CLEANUP_RULES = builder
                .comment(
                        "Keeps a persistent cleanup rule after the currently loaded chunks are clean.",
                        "This is the low-cost answer for generated but unloaded chunks: when they are loaded later, they are scanned and cleaned.",
                        "Disable this if you only want to affect chunks that are loaded at the moment of the break."
                )
                .define("persistCleanupRules", true);

        PREVENT_NON_ENTITY_PLACEMENT_WITH_MIXIN = builder
                .comment(
                        "Uses a mixin on Level#setBlock to prevent queued or persistent cleanup-target blocks from being placed by non-entity world logic.",
                        "Player/item placement through NeoForge's placement hook is allowed.",
                        "Default is false because this touches a very hot vanilla path and may affect other mods that set blocks directly."
                )
                .define("preventNonEntityPlacementWithMixin", false);

        MAX_ACTIVE_TARGETS = builder
                .comment("Maximum number of different block types that can be queued in one dimension at the same time.")
                .defineInRange("maxActiveTargets", 32, 1, 4096);

        BLOCK_DENYLIST = builder
                .comment(
                        "Blocks that should never trigger or be added to same-block cleanup.",
                        "Use registry ids such as minecraft:bedrock."
                )
                .defineListAllowEmpty(
                        "blockDenylist",
                        List.of(
                                "minecraft:air",
                                "minecraft:void_air",
                                "minecraft:cave_air"
                        ),
                        () -> "minecraft:air",
                        value -> value instanceof String
                );

        builder.pop();
        builder.push("messages");

        SEND_START_MESSAGE = builder
                .comment("Sends a localized message to the triggering player when cleanup starts.")
                .define("sendStartMessage", true);

        SEND_COMPLETION_MESSAGE = builder
                .comment("Sends a localized message to the triggering player when the current loaded-chunk pass finishes.")
                .define("sendCompletionMessage", true);

        builder.pop();
        builder.push("queue");

        MAX_SECTIONS_PER_TICK = builder
                .comment("Maximum number of 16x16x16 sections that can finish scanning per level tick.")
                .defineInRange("maxSectionsPerTick", 8, 1, 1024);

        MAX_SCANNED_BLOCKS_PER_TICK = builder
                .comment("Maximum number of block positions scanned per level tick.")
                .defineInRange("maxScannedBlocksPerTick", 32768, 256, Integer.MAX_VALUE);

        MAX_BLOCK_CHANGES_PER_TICK = builder
                .comment("Maximum number of block changes performed per level tick.")
                .defineInRange("maxBlockChangesPerTick", 4096, 1, Integer.MAX_VALUE);

        builder.pop();
        builder.push("nearbyNormalDestroy");

        NORMAL_DESTROY_SECTION_RADIUS = builder
                .comment(
                        "Sections within this radius from the triggering entity use normal Level#destroyBlock logic.",
                        "A value of 1 means a 3x3x3 section cube around the entity."
                )
                .defineInRange("normalDestroySectionRadius", 1, 0, 64);

        NORMAL_DESTROY_DROP_LIMIT = builder
                .comment("Maximum number of automatically destroyed nearby blocks that may drop resources per trigger.")
                .defineInRange("normalDestroyDropLimit", 128, 0, Integer.MAX_VALUE);

        NORMAL_DESTROY_UPDATE_LIMIT = builder
                .comment("Neighbor update limit used by normal Level#destroyBlock calls.")
                .defineInRange("normalDestroyUpdateLimit", 64, 0, 512);

        builder.pop();
        builder.push("farFastRemoval");

        FAST_REMOVAL_FLAGS = builder
                .comment(
                        "Update flags used for distant fast removal.",
                        "Default = UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE | UPDATE_SKIP_ON_PLACE.",
                        "Keeping UPDATE_CLIENTS makes loaded clients see the cleanup without enabling full neighbor updates."
                )
                .defineInRange("fastRemovalFlags", Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE, 0, 2047);

        FAST_REMOVAL_UPDATE_LIMIT = builder
                .comment("Neighbor update limit used by distant fast removal.")
                .defineInRange("fastRemovalUpdateLimit", 0, 0, 512);

        builder.pop();
        builder.push("supportCleanup");

        CLEANUP_UNSUPPORTED_BLOCKS = builder
                .comment("After fast removal, checks nearby blocks and removes blocks that can no longer survive, such as torches or vegetation.")
                .define("cleanupUnsupportedBlocks", true);

        SUPPORT_CHECKS_PER_TICK = builder
                .comment("Maximum number of support-cleanup checks per level tick.")
                .defineInRange("supportChecksPerTick", 4096, 0, Integer.MAX_VALUE);

        SUPPORT_CASCADE_DEPTH = builder
                .comment("Maximum cascade depth for unsupported-block cleanup around each removed block.")
                .defineInRange("supportCascadeDepth", 8, 0, 64);

        builder.pop();

        SPEC = builder.build();
    }

    private SameBlockBreakConfig() {
    }
}
