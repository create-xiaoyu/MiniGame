package com.xiaoyu.minigame.mixin;

import com.xiaoyu.minigame.gamefeature.sameblockbreak.SameBlockBreakManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BucketItem.class)
public abstract class BucketItemMixin {
    @Inject(method = "use", at = @At("HEAD"))
    private void minigame$beginSameBlockBreakBucketPlacementBypass(
            Level level,
            Player player,
            InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        SameBlockBreakManager.beginEntityPlacementBypass();
    }

    @Inject(method = "use", at = @At("RETURN"))
    private void minigame$endSameBlockBreakBucketPlacementBypass(
            Level level,
            Player player,
            InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        SameBlockBreakManager.endEntityPlacementBypass();
    }

    @Redirect(
            method = "use",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/BucketPickup;pickupBlock(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/item/ItemStack;"
            )
    )
    private ItemStack minigame$startSameBlockBreakFluidPickup(
            BucketPickup bucketPickup,
            LivingEntity user,
            LevelAccessor levelAccessor,
            BlockPos pos,
            BlockState state,
            Level level,
            Player player,
            InteractionHand hand
    ) {
        ItemStack result = bucketPickup.pickupBlock(user, levelAccessor, pos, state);
        if (!result.isEmpty() && level instanceof ServerLevel serverLevel) {
            SameBlockBreakManager.startFromBucketPickup(serverLevel, pos, state, player);
        }
        return result;
    }
}
