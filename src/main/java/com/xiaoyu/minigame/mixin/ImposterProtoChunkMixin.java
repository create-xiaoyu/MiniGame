package com.xiaoyu.minigame.mixin;

import com.xiaoyu.minigame.gamefeature.sameblockbreak.SameBlockBreakManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ImposterProtoChunk.class)
public abstract class ImposterProtoChunkMixin {
    @Inject(
            method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void minigame$preventSameBlockBreakImposterProtoChunkPlacement(
            BlockPos pos,
            BlockState blockState,
            int updateFlags,
            CallbackInfoReturnable<BlockState> cir
    ) {
        if (SameBlockBreakManager.shouldPreventNonEntityPlacement(blockState)) {
            cir.setReturnValue(((ImposterProtoChunk) (Object) this).getBlockState(pos));
        }
    }
}
