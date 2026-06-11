package com.xiaoyu.minigame.gamefeature.common;

import com.xiaoyu.minigame.gamefeature.common.chunk.ChunkTracker;
import com.xiaoyu.minigame.gamefeature.common.commands.CommonCommands;
import com.xiaoyu.minigame.gamefeature.common.config.CommonConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

public class CommonFeature {
    public static void register(ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, CommonConfig.SPEC, "minigame/common.toml");

        NeoForge.EVENT_BUS.addListener(CommonFeature::ServerStoppedEvent);

        NeoForge.EVENT_BUS.register(ChunkTracker.class);
        NeoForge.EVENT_BUS.register(CommonCommands.class);
    }

    private static void ServerStoppedEvent(ServerStoppedEvent event) {
        ChunkTracker.clearAll();
    }
}
