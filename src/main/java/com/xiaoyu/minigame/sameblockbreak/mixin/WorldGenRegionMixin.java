package com.xiaoyu.minigame.sameblockbreak.mixin;

import com.xiaoyu.minigame.sameblockbreak.world.ForbiddenBlocks;

import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionMixin {
    @ModifyVariable(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private BlockState minigame$replaceForbiddenBlock(BlockState state) {
        return ForbiddenBlocks.replacementFor(state);
    }
}
