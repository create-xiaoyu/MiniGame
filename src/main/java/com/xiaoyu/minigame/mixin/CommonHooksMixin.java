package com.xiaoyu.minigame.mixin;

import com.xiaoyu.minigame.gamefeature.sameblockbreak.SameBlockBreakManager;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.neoforged.neoforge.common.CommonHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CommonHooks.class, remap = false)
public abstract class CommonHooksMixin {
    @Inject(method = "onPlaceItemIntoWorld", at = @At("HEAD"))
    private static void minigame$beginSameBlockBreakEntityPlacementBypass(
            UseOnContext context,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        SameBlockBreakManager.beginEntityPlacementBypass();
    }

    @Inject(method = "onPlaceItemIntoWorld", at = @At("RETURN"))
    private static void minigame$endSameBlockBreakEntityPlacementBypass(
            UseOnContext context,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        SameBlockBreakManager.endEntityPlacementBypass();
    }
}
