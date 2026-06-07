package com.xiaoyu.minigame.chunkplaceblock;

import java.util.List;

import com.xiaoyu.minigame.MiniGame;
import com.xiaoyu.minigame.chunkplaceblock.command.ChunkPlaceBlockCommands;
import com.xiaoyu.minigame.chunkplaceblock.config.ChunkPlaceBlockConfig;
import com.xiaoyu.minigame.chunkplaceblock.placement.ChunkPlaceBlockManager;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class ChunkPlaceBlockFeature {
    private ChunkPlaceBlockFeature() {
    }

    public static void broadcastPlacement(ServerLevel level, Entity entity, BlockState placedBlock) {
        if (!ChunkPlaceBlockConfig.ENABLED.getAsBoolean()) {
            return;
        }

        Component message = Component.translatable("minigame.chunkplaceblock.broadcast.trigger",
                entity.getDisplayName(),
                placedBlock.getBlock().getName()
                ).withStyle(ChatFormatting.RED);

        level.getServer().getPlayerList().broadcastSystemMessage(message, false);
    }

    public static void register(ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, ChunkPlaceBlockConfig.SPEC, "minigame/chunkplaceblock.toml");

        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, BlockEvent.EntityMultiPlaceEvent.class, ChunkPlaceBlockFeature::onMultiPlace);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, BlockEvent.EntityPlaceEvent.class, ChunkPlaceBlockFeature::onPlace);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, BreakBlockEvent.class, ChunkPlaceBlockFeature::onBreakBlock);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ChunkEvent.Load.class, ChunkPlaceBlockFeature::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, ChunkPlaceBlockCommands::registerCommands);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, ChunkPlaceBlockFeature::onServerTick);
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, ChunkPlaceBlockFeature::onServerStopping);
    }

    private static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event instanceof BlockEvent.EntityMultiPlaceEvent) {
            return;
        }

        if (event.isCanceled() || ChunkPlaceBlockManager.isInternalUpdate()) {
            return;
        }

        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        broadcastPlacement(level, event.getEntity(), event.getPlacedBlock());
        ChunkPlaceBlockManager.INSTANCE.schedulePlacement(level, List.of(event.getPos()));
    }

    private static void onMultiPlace(BlockEvent.EntityMultiPlaceEvent event) {
        if (event.isCanceled() || ChunkPlaceBlockManager.isInternalUpdate()) {
            return;
        }

        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        List<BlockPos> positions = event.getReplacedBlockSnapshots().stream()
                .map(BlockSnapshot::getPos)
                .toList();
        broadcastPlacement(level, event.getEntity(), event.getPlacedBlock());
        ChunkPlaceBlockManager.INSTANCE.schedulePlacement(level, positions);
    }

    private static void onBreakBlock(BreakBlockEvent event) {
        if (event.isCanceled() || ChunkPlaceBlockManager.isInternalUpdate()) {
            return;
        }

        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        ChunkPlaceBlockManager.INSTANCE.startBreak(level, event.getState(), event.getPos());
    }

    private static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        ChunkPlaceBlockManager.INSTANCE.scheduleLoadedChunk(level, event.getChunk());
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        ChunkPlaceBlockManager.INSTANCE.tick(event.getServer());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        int cancelled = ChunkPlaceBlockManager.INSTANCE.cancelAll();
        if (cancelled > 0) {
            MiniGame.LOGGER.info("Cancelled {} ChunkPlaceBlock task(s) during server shutdown", cancelled);
        }
    }
}
