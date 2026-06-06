package com.xiaoyu.minigame.sameblockbreak.mixin;

import com.xiaoyu.minigame.sameblockbreak.world.ForbiddenBlocks;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Inject(
            method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD")
    )
    private void minigame$beginPlayerPlacement(BlockPlaceContext placeContext, CallbackInfoReturnable<InteractionResult> callback) {
        if (placeContext.getPlayer() != null) {
            ForbiddenBlocks.beginPlayerPlacement();
        }
    }

    @Inject(
            method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",
            at = @At("RETURN")
    )
    private void minigame$endPlayerPlacement(BlockPlaceContext placeContext, CallbackInfoReturnable<InteractionResult> callback) {
        if (placeContext.getPlayer() != null) {
            ForbiddenBlocks.endPlayerPlacement();
        }
    }
}
