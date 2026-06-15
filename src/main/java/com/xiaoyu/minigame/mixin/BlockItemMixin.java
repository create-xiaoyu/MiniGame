package com.xiaoyu.minigame.mixin;

import com.xiaoyu.minigame.gamefeature.sameblockbreak.SameBlockBreakManager;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Inject(method = "place", at = @At("HEAD"))
    private void minigame$beginSameBlockBreakBlockItemPlacementBypass(
            BlockPlaceContext placeContext,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        if (!placeContext.getLevel().isClientSide()) {
            SameBlockBreakManager.beginEntityPlacementBypass();
        }
    }

    @Inject(method = "place", at = @At("RETURN"))
    private void minigame$endSameBlockBreakBlockItemPlacementBypass(
            BlockPlaceContext placeContext,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        if (!placeContext.getLevel().isClientSide()) {
            SameBlockBreakManager.endEntityPlacementBypass();
        }
    }
}
