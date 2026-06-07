package com.xiaoyu.minigame.mixin.sameblockbreak;

import com.xiaoyu.minigame.sameblockbreak.world.ForbiddenBlocks;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
    @Inject(
            method = "debugPreliminarySurfaceLevel(Lnet/minecraft/world/level/levelgen/NoiseChunk;IIILnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN"),
            cancellable = true
    )
    private void minigame$replaceForbiddenTerrainBlock(
            NoiseChunk noiseChunk,
            int posX,
            int posY,
            int posZ,
            BlockState state,
            CallbackInfoReturnable<BlockState> callback
    ) {
        callback.setReturnValue(ForbiddenBlocks.replacementFor(callback.getReturnValue()));
    }
}
