package com.xiaoyu.minigame.mixin.chunkplaceblock;

import java.util.List;

import com.xiaoyu.minigame.chunkplaceblock.placement.ChunkPlaceBlockManager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BucketItem.class)
public abstract class BucketItemMixin {
    @Shadow
    @Final
    public Fluid content;

    @Inject(
            method = "emptyContents(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/BlockHitResult;Lnet/minecraft/world/item/ItemStack;)Z",
            at = @At("RETURN")
    )
    private void minigame$scheduleChunkFluidPlacement(
            @Nullable LivingEntity user,
            Level level,
            BlockPos pos,
            @Nullable BlockHitResult hitResult,
            @Nullable ItemStack containerItem,
            CallbackInfoReturnable<Boolean> callback
    ) {
        if (!callback.getReturnValue() || ChunkPlaceBlockManager.isInternalUpdate()) {
            return;
        }

        if (!(level instanceof ServerLevel serverLevel) || content != Fluids.WATER && content != Fluids.LAVA) {
            return;
        }

        Block placedFluidBlock = content.defaultFluidState().createLegacyBlock().getBlock();
        if (serverLevel.getBlockState(pos).getBlock() != placedFluidBlock
                || !serverLevel.getFluidState(pos).isSourceOfType(content)) {
            return;
        }

        ChunkPlaceBlockManager.INSTANCE.schedulePlacement(serverLevel, List.of(pos.immutable()));
    }
}
