package com.xiaoyu.minigame.gamefeature.sameblockbreak.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.xiaoyu.minigame.gamefeature.sameblockbreak.SameBlockBreakManager;
import com.xiaoyu.minigame.gamefeature.sameblockbreak.config.SameBlockBreakConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class SameBlockBreakCommands {
    private SameBlockBreakCommands() {
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("minigame")
                        .then(Commands.literal("sameblockbreak")
                                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
                                .then(Commands.literal("status")
                                        .executes(context -> status(context.getSource())))
                                .then(Commands.literal("start")
                                        .then(Commands.argument("block", StringArgumentType.word())
                                                .executes(context -> start(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "block")
                                                ))))
                                .then(Commands.literal("forget")
                                        .then(Commands.argument("block", StringArgumentType.word())
                                                .executes(context -> forget(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "block")
                                                ))))
                                .then(Commands.literal("clearRules")
                                        .executes(context -> clearRules(context.getSource())))
                                .then(Commands.literal("disable")
                                        .executes(context -> disable(context.getSource())))
                                .then(Commands.literal("enable")
                                        .executes(context -> enable(context.getSource())))
                        )
        );
    }

    private static int status(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        SameBlockBreakManager.SameBlockBreakStatus status = SameBlockBreakManager.status(level);
        source.sendSystemMessage(Component.translatable(
                "command.minigame.sameblockbreak.status",
                status.activeTargets(),
                status.queuedSections(),
                status.completedSections(),
                status.supportChecks(),
                status.persistedRules()
        ));
        return 1;
    }

    private static int start(CommandSourceStack source, String blockId) {
        SameBlockBreakManager.StartResult result = SameBlockBreakManager.startFromCommand(
                source.getLevel(),
                blockId,
                BlockPos.containing(source.getPosition()),
                source.getEntity()
        );

        switch (result) {
            case STARTED -> {
                source.sendSystemMessage(Component.translatable("command.minigame.sameblockbreak.start.success", blockId));
                return 1;
            }
            case ALREADY_RUNNING -> source.sendSystemMessage(Component.translatable("command.minigame.sameblockbreak.start.already_running", blockId));
            case MAX_ACTIVE_TARGETS -> source.sendSystemMessage(Component.translatable(
                    "command.minigame.sameblockbreak.start.max_active",
                    SameBlockBreakConfig.MAX_ACTIVE_TARGETS.getAsInt()
            ));
            case INVALID_BLOCK -> source.sendSystemMessage(Component.translatable("command.minigame.sameblockbreak.unknown_block", blockId));
        }

        return 0;
    }

    private static int forget(CommandSourceStack source, String blockId) {
        boolean removed = SameBlockBreakManager.forget(source.getLevel(), blockId);
        if (removed) {
            source.sendSystemMessage(Component.translatable("command.minigame.sameblockbreak.forget.success", blockId));
            return 1;
        }

        source.sendSystemMessage(Component.translatable("command.minigame.sameblockbreak.forget.not_found", blockId));
        return 0;
    }

    private static int clearRules(CommandSourceStack source) {
        int removed = SameBlockBreakManager.clearRules(source.getLevel());
        source.sendSystemMessage(Component.translatable("command.minigame.sameblockbreak.clear.success", removed));
        return removed;
    }

    private static int disable(CommandSourceStack source) {
        if (SameBlockBreakConfig.ENABLED.get()) {
            SameBlockBreakManager.clearAll();

            SameBlockBreakConfig.ENABLED.set(false);
            SameBlockBreakConfig.ENABLED.save();

            source.sendSystemMessage(Component.translatable("command.minigame.sameblockbreak.disable.success"));
        } else {
            source.sendSystemMessage(Component.translatable("command.minigame.sameblockbreak.disable.failure"));
        }

        return 1;
    }

    private static int enable(CommandSourceStack source) {
        if (SameBlockBreakConfig.ENABLED.get()) {
            source.sendSystemMessage(Component.translatable("command.minigame.sameblockbreak.enable.failure"));
        } else {
            SameBlockBreakConfig.ENABLED.set(true);
            SameBlockBreakConfig.ENABLED.save();

            source.sendSystemMessage(Component.translatable("command.minigame.sameblockbreak.enable.success"));
        }

        return 1;
    }
}
