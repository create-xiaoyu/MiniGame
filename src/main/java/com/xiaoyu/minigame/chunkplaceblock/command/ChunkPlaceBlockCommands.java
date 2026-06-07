package com.xiaoyu.minigame.chunkplaceblock.command;

import com.mojang.brigadier.CommandDispatcher;
import com.xiaoyu.minigame.chunkplaceblock.config.ChunkPlaceBlockConfig;
import com.xiaoyu.minigame.chunkplaceblock.placement.ChunkPlaceBlockManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public class ChunkPlaceBlockCommands {
    private ChunkPlaceBlockCommands() {
    }

    public static void registerCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                Commands.literal("minigame").then(
                        Commands.literal("chunkplaceblock").requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
                                .then(

                                        Commands.literal("enable").executes(c -> enable(c.getSource()))
                                )
                                .then(
                                        Commands.literal("disable").executes(c -> disable(c.getSource()))
                                )
                )
        );
    }

    private static int enable(CommandSourceStack source) {
        if (ChunkPlaceBlockConfig.ENABLED.getAsBoolean()) {
            source.sendSystemMessage(Component.translatable("minigame.command.enable.enable", "ChunkPlaceBlock"));

            return 1;
        }

        ChunkPlaceBlockConfig.ENABLED.set(true);
        ChunkPlaceBlockConfig.SPEC.save();

        source.sendSystemMessage(Component.translatable("minigame.command.enable", "ChunkPlaceBlock"));
        return 1;
    }

    private static int disable(CommandSourceStack source) {
        if (!ChunkPlaceBlockConfig.ENABLED.getAsBoolean()) {
            source.sendSystemMessage(Component.translatable("minigame.command.disable.disable", "ChunkPlaceBlock"));
            return 1;
        }

        ChunkPlaceBlockManager.INSTANCE.cancelAll();
        ChunkPlaceBlockConfig.ENABLED.set(false);
        ChunkPlaceBlockConfig.SPEC.save();

        source.sendSystemMessage(Component.translatable("minigame.command.disable", "ChunkPlaceBlock"));
        return 1;
    }
}
