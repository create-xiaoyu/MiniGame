package com.xiaoyu.minigame.mixin.chunkplaceblock;

import com.xiaoyu.minigame.gamefeature.chunkplaceblock.placement.ChunkPlaceBlockManager;

import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {
    @Inject(method = "setChanged()V", at = @At("TAIL"))
    private void minigame$syncChunkPlaceBlockData(CallbackInfo callback) {
        ChunkPlaceBlockManager.INSTANCE.onBlockEntityChanged((BlockEntity) (Object) this);
    }
}
