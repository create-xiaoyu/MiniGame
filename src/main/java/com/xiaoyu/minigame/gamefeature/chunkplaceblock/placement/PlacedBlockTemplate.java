package com.xiaoyu.minigame.gamefeature.chunkplaceblock.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

public record PlacedBlockTemplate(
        BlockPos sourcePos,
        BlockState state,
        @Nullable CompoundTag blockEntityTag
) {
    public PlacedBlockTemplate {
        sourcePos = sourcePos.immutable();
        blockEntityTag = blockEntityTag == null ? null : blockEntityTag.copy();
    }

    public BlockPos targetPos(int chunkX, int chunkZ) {
        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        return chunkPos.getBlockAt(sourcePos.getX() & 15, sourcePos.getY(), sourcePos.getZ() & 15);
    }

    public boolean hasBlockEntityData() {
        return blockEntityTag != null;
    }

    public CompoundTag blockEntityTagFor(BlockPos targetPos) {
        CompoundTag tag = blockEntityTag == null ? new CompoundTag() : blockEntityTag.copy();
        tag.putInt("x", targetPos.getX());
        tag.putInt("y", targetPos.getY());
        tag.putInt("z", targetPos.getZ());
        return tag;
    }
}
