package com.xiaoyu.minigame.gamefeature.sameblockbreak.world;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mojang.serialization.Codec;
import com.xiaoyu.minigame.MiniGame;
import com.xiaoyu.minigame.gamefeature.sameblockbreak.config.SameBlockBreakConfig;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public final class ForbiddenBlocks extends SavedData {
    private static final Identifier DATA_ID = Identifier.fromNamespaceAndPath(MiniGame.MODID, "forbidden_blocks");
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final ThreadLocal<Integer> PLAYER_PLACEMENT_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static volatile Set<Identifier> activeBlockIds = Set.of();

    public static final Codec<ForbiddenBlocks> CODEC = Identifier.CODEC.listOf()
            .xmap(ForbiddenBlocks::new, ForbiddenBlocks::idsForCodec);
    public static final SavedDataType<ForbiddenBlocks> TYPE = new SavedDataType<>(DATA_ID, ForbiddenBlocks::new, CODEC);

    private final Set<Identifier> blockIds = new HashSet<>();

    public ForbiddenBlocks() {
    }

    private ForbiddenBlocks(List<Identifier> blockIds) {
        this.blockIds.addAll(blockIds);
        refreshActiveCache();
    }

    public static ForbiddenBlocks get(MinecraftServer server) {
        ForbiddenBlocks data = server.overworld().getDataStorage().computeIfAbsent(TYPE);
        data.refreshActiveCache();
        return data;
    }

    public static void clearActiveCache() {
        activeBlockIds = Set.of();
    }

    public static void beginPlayerPlacement() {
        PLAYER_PLACEMENT_DEPTH.set(PLAYER_PLACEMENT_DEPTH.get() + 1);
    }

    public static void endPlayerPlacement() {
        int depth = PLAYER_PLACEMENT_DEPTH.get();
        if (depth <= 1) {
            PLAYER_PLACEMENT_DEPTH.remove();
        } else {
            PLAYER_PLACEMENT_DEPTH.set(depth - 1);
        }
    }

    public boolean add(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        return add(state.getBlock());
    }

    public boolean add(Block block) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        if (id == null || block == Blocks.AIR) {
            return false;
        }

        if (blockIds.add(id)) {
            refreshActiveCache();
            setDirty();
            MiniGame.LOGGER.info("Permanently forbidding generated block {}", id);
            return true;
        }
        return false;
    }

    public static BlockState replacementFor(BlockState state) {
        if (state == null
                || state.isAir()
                || PLAYER_PLACEMENT_DEPTH.get() > 0
                || !SameBlockBreakConfig.REMEMBER_BROKEN_BLOCKS_FOREVER.getAsBoolean()) {
            return state;
        }

        Set<Identifier> forbidden = activeBlockIds;
        if (containsBlock(forbidden, state.getBlock())) {
            return AIR;
        }

        if (hasForbiddenWater(forbidden, state)) {
            if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) {
                return state.setValue(BlockStateProperties.WATERLOGGED, false);
            }
            return AIR;
        }

        if (hasForbiddenLava(forbidden, state)) {
            return AIR;
        }

        return state;
    }

    private static boolean hasForbiddenWater(Set<Identifier> forbidden, BlockState state) {
        return containsBlock(forbidden, Blocks.WATER) && hasFluid(state, Fluids.WATER);
    }

    private static boolean hasForbiddenLava(Set<Identifier> forbidden, BlockState state) {
        return containsBlock(forbidden, Blocks.LAVA) && hasFluid(state, Fluids.LAVA);
    }

    private static boolean hasFluid(BlockState state, Fluid fluid) {
        return state.getFluidState().getType().isSame(fluid);
    }

    private static boolean containsBlock(Set<Identifier> forbidden, Block block) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        return id != null && forbidden.contains(id);
    }

    private List<Identifier> idsForCodec() {
        return blockIds.stream().sorted().toList();
    }

    private void refreshActiveCache() {
        activeBlockIds = Set.copyOf(blockIds);
    }
}
