package com.xiaoyu.minigame.mixin.sameblockbreak;

import com.xiaoyu.minigame.sameblockbreak.world.ForbiddenBlocks;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Level.class)
public abstract class LevelMixin {
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
