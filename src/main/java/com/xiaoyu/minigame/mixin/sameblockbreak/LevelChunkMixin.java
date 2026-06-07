package com.xiaoyu.minigame.mixin.sameblockbreak;

import com.xiaoyu.minigame.sameblockbreak.world.ForbiddenBlocks;

import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @ModifyVariable(
            method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private BlockState minigame$replaceForbiddenBlock(BlockState state) {
        return ForbiddenBlocks.replacementFor(state);
    }
}
