package com.xiaoyu.minigame.sameblockbreak.mixin;

import com.xiaoyu.minigame.sameblockbreak.world.ForbiddenBlocks;

import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ProtoChunk.class)
public abstract class ProtoChunkMixin {
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
