package com.xiaoyu.minigame;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.xiaoyu.minigame.config.MiniGameConfig;
import com.xiaoyu.minigame.gamefeature.chunkplaceblock.ChunkPlaceBlockFeature;
import com.xiaoyu.minigame.gamefeature.sameblockbreak.SameBlockBreakFeature;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(MiniGame.MODID)
public class MiniGame {
    public static final String MODID = "minigame";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MiniGame(ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, MiniGameConfig.SPEC, "minigame/common.toml");
        SameBlockBreakFeature.register(modContainer);
        ChunkPlaceBlockFeature.register(modContainer);
    }

    public static void debug(String message, Object... arguments) {
        if (MiniGameConfig.DEBUG_LOGGING.getAsBoolean()) {
            LOGGER.debug(message, arguments);
        }
    }
}
