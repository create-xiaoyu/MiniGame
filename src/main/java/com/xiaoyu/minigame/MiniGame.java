package com.xiaoyu.minigame;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.xiaoyu.minigame.sameblockbreak.SameBlockBreakFeature;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(MiniGame.MODID)
public class MiniGame {
    public static final String MODID = "minigame";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MiniGame(ModContainer modContainer) {
        SameBlockBreakFeature.register(modContainer);
    }
}
