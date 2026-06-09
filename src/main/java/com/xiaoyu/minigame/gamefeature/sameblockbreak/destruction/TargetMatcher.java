package com.xiaoyu.minigame.gamefeature.sameblockbreak.destruction;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

public final class TargetMatcher {
    private final boolean matchBlockType;
    private final Block targetBlock;

    private TargetMatcher(boolean matchBlockType, Block targetBlock) {
        this.matchBlockType = matchBlockType;
        this.targetBlock = targetBlock;
    }

    public static TargetMatcher from(BlockState triggerState, boolean matchBlockType) {
        return new TargetMatcher(matchBlockType, matchBlockType ? triggerState.getBlock() : null);
    }

    public boolean matches(BlockState state) {
        if (state.isAir()) {
            return false;
        }

        if (!matchBlockType) {
            return true;
        }

        if (targetBlock == Blocks.WATER) {
            return state.getBlock() == Blocks.WATER || hasFluid(state, Fluids.WATER);
        }

        if (targetBlock == Blocks.LAVA) {
            return state.getBlock() == Blocks.LAVA || hasFluid(state, Fluids.LAVA);
        }

        return state.getBlock() == targetBlock;
    }

    public boolean shouldDryWaterlogged(BlockState state) {
        return matchBlockType
                && targetBlock == Blocks.WATER
                && state.hasProperty(BlockStateProperties.WATERLOGGED)
                && state.getValue(BlockStateProperties.WATERLOGGED);
    }

    public boolean isTargetFluid(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.WATER
                || block == Blocks.LAVA
                || block == Blocks.BUBBLE_COLUMN
                || targetBlock == Blocks.WATER && hasFluid(state, Fluids.WATER)
                || targetBlock == Blocks.LAVA && hasFluid(state, Fluids.LAVA);
    }

    private boolean hasFluid(BlockState state, Fluid fluid) {
        return state.getFluidState().getType().isSame(fluid);
    }

    public String description() {
        if (!matchBlockType) {
            return "all non-air blocks";
        }

        Identifier id = BuiltInRegistries.BLOCK.getKey(targetBlock);
        return id == null ? targetBlock.toString() : id.toString();
    }

    public Component displayName() {
        return matchBlockType
                ? targetBlock.getName()
                : Component.translatable("minigame.sameblockbreak.target.all_non_air");
    }
}
