package com.xiaoyu.minigame.gamefeature.hurtchunkbreak;

import com.xiaoyu.minigame.gamefeature.common.chunk.ChunkTracker;
import com.xiaoyu.minigame.gamefeature.hurtchunkbreak.config.HurtChunkBreakConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

public class HurtChunkBreakFeature {
    public static void register(ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, HurtChunkBreakConfig.SPEC, "minigame/hurtchunkbreak.toml");

        NeoForge.EVENT_BUS.addListener(HurtChunkBreakFeature::onLivingDamageEvent);
    }

    private static void onLivingDamageEvent(LivingDamageEvent.Post event) {
        if (!HurtChunkBreakConfig.ENABLED.get()) return;

        if (event.getEntity() instanceof ServerPlayer player) {
            ServerLevel level = player.level();
            ChunkTracker.PlayerChunk playerChunk = ChunkTracker.getPlayerChunk(player);

            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

            for (int x = playerChunk.minX(); x <= playerChunk.maxX(); x++) {
                for (int y = playerChunk.minY(); y <= playerChunk.maxY(); y++) {
                    for (int z = playerChunk.minZ(); z <= playerChunk.maxZ(); z++) {
                        pos.set(x, y, z);
                        if (!level.getBlockState(pos).isAir()) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                        }
                    }
                }
            }
        }
    }
}
