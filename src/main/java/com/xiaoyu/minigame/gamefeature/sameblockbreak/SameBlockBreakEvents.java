package com.xiaoyu.minigame.gamefeature.sameblockbreak;

import com.xiaoyu.minigame.gamefeature.sameblockbreak.config.SameBlockBreakConfig;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

public final class SameBlockBreakEvents {
    private SameBlockBreakEvents() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreakBlock(BreakBlockEvent event) {
        if (!SameBlockBreakConfig.TRIGGER_PLAYER_BREAKS.get() || event.isCanceled()) {
            return;
        }

        if (event.getLevel() instanceof ServerLevel level) {
            SameBlockBreakManager.startFromBreak(level, event.getPos(), event.getState(), event.getPlayer());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDestroyBlock(LivingDestroyBlockEvent event) {
        if (!SameBlockBreakConfig.TRIGGER_LIVING_ENTITY_BREAKS.get() || event.isCanceled()) {
            return;
        }

        if (event.getEntity().level() instanceof ServerLevel level) {
            SameBlockBreakManager.startFromBreak(level, event.getPos(), event.getState(), event.getEntity());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLevelTickPost(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            SameBlockBreakManager.tick(level);
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            SameBlockBreakManager.onChunkUnload(level, event.getChunk().getPos().pack());
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            SameBlockBreakManager.onLevelUnload(level);
        }
    }
}
