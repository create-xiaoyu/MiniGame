package com.xiaoyu.minigame.mixin;

import com.xiaoyu.minigame.gamefeature.sameblockbreak.SameBlockBreakManager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunkSection.class)
public abstract class LevelChunkSectionMixin {
    @Inject(
            method = "setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void minigame$preventSameBlockBreakSectionPlacement(
            int sectionX,
            int sectionY,
            int sectionZ,
            BlockState blockState,
            boolean checkThreading,
            CallbackInfoReturnable<BlockState> cir
    ) {
        if (SameBlockBreakManager.shouldPreventNonEntityPlacement(blockState)) {
            cir.setReturnValue(((LevelChunkSection) (Object) this).getBlockState(sectionX, sectionY, sectionZ));
        }
    }
}
