package com.xiaoyu.minigame.gamefeature.sameblockbreak;

import com.xiaoyu.minigame.gamefeature.sameblockbreak.commands.SameBlockBreakCommands;
import com.xiaoyu.minigame.gamefeature.sameblockbreak.config.SameBlockBreakConfig;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

public final class SameBlockBreakFeature {

    public static void register(ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, SameBlockBreakConfig.SPEC, "minigame/sameblockbreak.toml");

        NeoForge.EVENT_BUS.addListener(SameBlockBreakFeature::onServerStopped);
        NeoForge.EVENT_BUS.addListener(SameBlockBreakFeature::onServerStarted);
        NeoForge.EVENT_BUS.register(SameBlockBreakEvents.class);
        NeoForge.EVENT_BUS.register(SameBlockBreakCommands.class);
    }

    private static void onServerStarted(ServerStartedEvent event) {
        SameBlockBreakManager.loadGlobalRules(event.getServer());
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        SameBlockBreakManager.clearAll();
    }
}
