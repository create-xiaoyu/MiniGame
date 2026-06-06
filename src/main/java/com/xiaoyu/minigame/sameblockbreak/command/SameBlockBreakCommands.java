package com.xiaoyu.minigame.sameblockbreak.command;

import com.mojang.brigadier.CommandDispatcher;
import com.xiaoyu.minigame.sameblockbreak.config.SameBlockBreakConfig;
import com.xiaoyu.minigame.sameblockbreak.destruction.DestructionManager;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class SameBlockBreakCommands {
    private SameBlockBreakCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                Commands.literal("minigame")
                        .then(Commands.literal("sameblockbreak")
                                .requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
                                .then(Commands.literal("status")
                                        .executes(context -> status(context.getSource())))
                                .then(Commands.literal("cancel")
                                        .executes(context -> cancelDimension(context.getSource())))
                                .then(Commands.literal("cancelall")
                                        .executes(context -> cancelAll(context.getSource())))
                                .then(Commands.literal("enable")
                                        .executes(context -> enable(context.getSource())))
                                .then(Commands.literal("disable")
                                        .executes(context -> disable(context.getSource()))))
        );
    }

    private static int status(CommandSourceStack source) {
        Component status = DestructionManager.INSTANCE.status(source.getLevel().dimension());
        source.sendSuccess(() -> status, false);
        return 1;
    }

    private static int cancelDimension(CommandSourceStack source) {
        int cancelled = DestructionManager.INSTANCE.cancelDimension(source.getLevel().dimension());
        source.sendSuccess(() -> Component.translatable("minigame.sameblockbreak.command.cancel.dimension", cancelled), true);
        return cancelled;
    }

    private static int cancelAll(CommandSourceStack source) {
        int cancelled = DestructionManager.INSTANCE.cancelAll();
        source.sendSuccess(() -> Component.translatable("minigame.sameblockbreak.command.cancel.all", cancelled), true);
        return cancelled;
    }

    private static int enable(CommandSourceStack source) {
        if (SameBlockBreakConfig.ENABLED.getAsBoolean()) {
            source.sendSystemMessage(Component.translatable("minigame.sameblockbreak.command.enable.enable"));
            return 1;
        }

        SameBlockBreakConfig.ENABLED.set(true);
        SameBlockBreakConfig.SPEC.save();
        source.sendSystemMessage(Component.translatable("minigame.sameblockbreak.command.enable"));
        return 1;
    }

    private static int disable(CommandSourceStack source) {
        if (!SameBlockBreakConfig.ENABLED.getAsBoolean()) {
            source.sendSystemMessage(Component.translatable("minigame.sameblockbreak.command.disable.disable"));
            return 1;
        }

        SameBlockBreakConfig.ENABLED.set(false);
        SameBlockBreakConfig.SPEC.save();
        source.sendSystemMessage(Component.translatable("minigame.sameblockbreak.command.disable"));
        return 1;
    }
}
