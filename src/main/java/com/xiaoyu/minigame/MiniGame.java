package com.xiaoyu.minigame;

import com.xiaoyu.minigame.gamefeature.common.CommonFeature;
import com.xiaoyu.minigame.gamefeature.hurtchunkbreak.HurtChunkBreakFeature;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.xiaoyu.minigame.gamefeature.chunkplaceblock.ChunkPlaceBlockFeature;
import com.xiaoyu.minigame.gamefeature.sameblockbreak.SameBlockBreakFeature;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(MiniGame.MODID)
public class MiniGame {
    public static final String MODID = "minigame";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MiniGame(ModContainer modContainer) {
        CommonFeature.register(modContainer);
        SameBlockBreakFeature.register(modContainer);
        ChunkPlaceBlockFeature.register(modContainer);
        HurtChunkBreakFeature.register(modContainer);
    }
}
