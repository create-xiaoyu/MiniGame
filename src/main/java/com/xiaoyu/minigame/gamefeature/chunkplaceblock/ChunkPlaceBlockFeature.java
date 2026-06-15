package com.xiaoyu.minigame.gamefeature.chunkplaceblock;

import com.xiaoyu.minigame.gamefeature.chunkplaceblock.commands.ChunkPlaceBlockCommands;
import com.xiaoyu.minigame.gamefeature.chunkplaceblock.config.ChunkPlaceBlockConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

public final class ChunkPlaceBlockFeature {

    public static void register(ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, ChunkPlaceBlockConfig.SPEC, "minigame/chunkplaceblock.toml");

        NeoForge.EVENT_BUS.addListener(ChunkPlaceBlockFeature::onServerStopped);
        NeoForge.EVENT_BUS.register(ChunkPlaceBlockEvents.class);
        NeoForge.EVENT_BUS.register(ChunkPlaceBlockCommands.class);
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        ChunkPlaceBlockManager.clearAll();
    }
}
