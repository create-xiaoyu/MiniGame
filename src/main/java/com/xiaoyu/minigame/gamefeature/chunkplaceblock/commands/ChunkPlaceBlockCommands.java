package com.xiaoyu.minigame.gamefeature.chunkplaceblock.commands;

import com.xiaoyu.minigame.gamefeature.chunkplaceblock.ChunkPlaceBlockManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class ChunkPlaceBlockCommands {
    private ChunkPlaceBlockCommands() {
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("minigame")
                        .then(Commands.literal("chunkplaceblock")
                                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
                                .then(Commands.literal("status")
                                        .executes(context -> status(context.getSource())))
                                .then(Commands.literal("clearRules")
                                        .executes(context -> clearRules(context.getSource())))
                        )
        );
    }

    private static int status(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        ChunkPlaceBlockManager.ChunkPlaceBlockStatus status = ChunkPlaceBlockManager.status(level);
        source.sendSystemMessage(Component.translatable(
                "command.minigame.chunkplaceblock.status",
                status.loadedChunks(),
                status.persistentRules(),
                status.processedPersistentChunks(),
                status.pendingBucketPlacements()
        ));
        return 1;
    }

    private static int clearRules(CommandSourceStack source) {
        int removed = ChunkPlaceBlockManager.clearRules(source.getLevel());
        source.sendSystemMessage(Component.translatable("command.minigame.chunkplaceblock.clear.success", removed));
        return removed;
    }
}
