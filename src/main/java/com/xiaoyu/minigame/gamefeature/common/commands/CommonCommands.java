package com.xiaoyu.minigame.gamefeature.common.commands;

import com.mojang.logging.LogUtils;
import com.xiaoyu.minigame.gamefeature.common.chunk.ChunkTracker;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

import java.util.Collection;

public class CommonCommands {
    public static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("minigame").then(
                        Commands.literal("debug")
                                .then(
                                        Commands.literal("getLoadedChunks")
                                                .requires(c -> c.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
                                                .executes(context -> {
                                                    CommandSourceStack source = context.getSource();
                                                    ServerLevel level = context.getSource().getLevel();

                                                    Collection<LevelChunk> loadedChunks = ChunkTracker.getLoadedChunks(level);

                                                    int count = 0;

                                                    for (LevelChunk chunk : loadedChunks) {
                                                        count += 1;
                                                        LOGGER.debug("Number: {}, Chunk: {}, State: {}", count, chunk.getPos(), chunk.getFullStatus().name());
                                                        level.getHeight();
                                                        level.getMaxY();
                                                        level.getMaxSectionY();
                                                    }

                                                    source.sendSystemMessage(Component.translatable("command.minigame.minigame.debug.getLoadedChunks", count));

                                                    return 1;
                                                })
                                )
                                .then(
                                        Commands.literal("getWorldHeight").executes(context -> {
                                            CommandSourceStack source = context.getSource();
                                            ServerLevel level = context.getSource().getLevel();

                                            source.sendSystemMessage(Component.literal(String.valueOf(level.getHeight())));
                                            return 1;
                                        })
                                )
                )
        );
    }
}
