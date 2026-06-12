package com.xiaoyu.minigame.mixin;

import com.xiaoyu.minigame.gamefeature.sameblockbreak.SameBlockBreakManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionMixin {
    @Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void minigame$preventSameBlockBreakWorldGenPlacement(
            BlockPos pos,
            BlockState blockState,
            int updateFlags,
            int updateLimit,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (SameBlockBreakManager.shouldPreventNonEntityPlacement(blockState)) {
            cir.setReturnValue(false);
        }
    }
}
