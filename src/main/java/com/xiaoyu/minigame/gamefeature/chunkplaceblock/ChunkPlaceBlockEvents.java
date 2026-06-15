package com.xiaoyu.minigame.gamefeature.chunkplaceblock;

import com.xiaoyu.minigame.gamefeature.chunkplaceblock.config.ChunkPlaceBlockConfig;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.List;

public final class ChunkPlaceBlockEvents {
    private ChunkPlaceBlockEvents() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEntityPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        if (event instanceof BlockEvent.EntityMultiPlaceEvent multiPlaceEvent) {
            List<BlockSnapshot> snapshots = multiPlaceEvent.getReplacedBlockSnapshots();
            ChunkPlaceBlockManager.syncFromPlacement(level, snapshots, event.getEntity(), true);
            return;
        }

        ChunkPlaceBlockManager.syncFromPlacement(level, List.of(event.getBlockSnapshot()), event.getEntity(), false);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled() || !(event.getEntity().level() instanceof ServerLevel level) || event.getFace() == null) {
            return;
        }

        ChunkPlaceBlockManager.captureBucketPlacementCandidate(
                level,
                event.getEntity(),
                event.getEntity().getItemInHand(event.getHand()),
                event.getPos(),
                event.getFace()
        );
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreakBlock(BreakBlockEvent event) {
        if (!ChunkPlaceBlockConfig.TRIGGER_PLAYER_BREAKS.get() || event.isCanceled()) {
            return;
        }

        if (event.getLevel() instanceof ServerLevel level) {
            ChunkPlaceBlockManager.syncFromBreak(level, event.getPos(), event.getState(), event.getPlayer());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDestroyBlock(LivingDestroyBlockEvent event) {
        if (!ChunkPlaceBlockConfig.TRIGGER_LIVING_ENTITY_BREAKS.get() || event.isCanceled()) {
            return;
        }

        if (event.getEntity().level() instanceof ServerLevel level) {
            ChunkPlaceBlockManager.syncFromBreak(level, event.getPos(), event.getState(), event.getEntity());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLevelTickPost(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ChunkPlaceBlockManager.tick(level);
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ChunkPlaceBlockManager.onChunkUnload(level, event.getChunk().getPos().pack());
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            ChunkPlaceBlockManager.onLevelUnload(level);
        }
    }
}
