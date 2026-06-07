package com.xiaoyu.minigame.chunkplaceblock.placement;

import java.util.List;

import com.xiaoyu.minigame.MiniGame;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.TagValueInput;

public final class ChunkPlaceBlockTask {
    static final int PLACE_FLAGS = Block.UPDATE_ALL;
    static final int SILENT_DESTROY_FLAGS = Block.UPDATE_CLIENTS
            | Block.UPDATE_SUPPRESS_DROPS
            | Block.UPDATE_KNOWN_SHAPE
            | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS;
    private static final int DATA_UPDATE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private final ChunkPlaceOperation operation;
    private final List<PlacedBlockTemplate> templates;
    private final BlockPos center;
    private final long[] chunkQueue;
    private final ChunkPlaceBlockSettings settings;
    private final long startedAtMillis = System.currentTimeMillis();

    private int chunkIndex;
    private int processedChunks;
    private int skippedChunks;
    private long blocksPlaced;
    private long blocksDestroyed;
    private long blockEntityCopies;
    private long occupiedTargets;
    private long mismatchedTargets;
    private boolean cancelled;
    private boolean completed;

    public ChunkPlaceBlockTask(
            ChunkPlaceOperation operation,
            List<PlacedBlockTemplate> templates,
            BlockPos center,
            long[] chunkQueue,
            ChunkPlaceBlockSettings settings
    ) {
        this.operation = operation;
        this.templates = List.copyOf(templates);
        this.center = center.immutable();
        this.chunkQueue = chunkQueue;
        this.settings = settings;
    }

    public boolean tick(ServerLevel level) {
        if (cancelled || completed) {
            return true;
        }

        long deadline = System.nanoTime() + settings.maxMsPerTick() * 1_000_000L;
        int chunksThisTick = 0;

        while (!completed && !cancelled) {
            if (System.nanoTime() >= deadline || chunksThisTick >= settings.chunksPerTick()) {
                return false;
            }

            if (chunkIndex >= chunkQueue.length) {
                completed = true;
                return true;
            }

            long chunkKey = chunkQueue[chunkIndex++];
            chunksThisTick++;
            LevelChunk chunk = getChunk(level, ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
            if (chunk == null) {
                skippedChunks++;
                processedChunks++;
                continue;
            }

            processChunk(level, chunk);
            processedChunks++;
        }

        return true;
    }

    public void cancel() {
        this.cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public ChunkPlaceOperation operation() {
        return operation;
    }

    public int totalChunks() {
        return chunkQueue.length;
    }

    public int processedChunks() {
        return processedChunks;
    }

    public int skippedChunks() {
        return skippedChunks;
    }

    public long blocksPlaced() {
        return blocksPlaced;
    }

    public long blockEntityCopies() {
        return blockEntityCopies;
    }

    public long blocksDestroyed() {
        return blocksDestroyed;
    }

    public long occupiedTargets() {
        return occupiedTargets;
    }

    public long mismatchedTargets() {
        return mismatchedTargets;
    }

    public long elapsedMillis() {
        return System.currentTimeMillis() - startedAtMillis;
    }

    public String summary() {
        return operation
                + " chunks=" + processedChunks + "/" + chunkQueue.length
                + " placed=" + blocksPlaced
                + " destroyed=" + blocksDestroyed
                + " blockEntityCopies=" + blockEntityCopies
                + " occupied=" + occupiedTargets
                + " mismatched=" + mismatchedTargets
                + " skippedChunks=" + skippedChunks
                + " elapsed=" + elapsedMillis() + " ms";
    }

    private void processChunk(ServerLevel level, LevelChunk chunk) {
        int chunkX = chunk.getPos().x();
        int chunkZ = chunk.getPos().z();
        for (PlacedBlockTemplate template : templates) {
            BlockPos targetPos = template.targetPos(chunkX, chunkZ);
            if (targetPos.equals(template.sourcePos()) || !level.isInWorldBounds(targetPos)) {
                continue;
            }

            if (operation == ChunkPlaceOperation.PLACE_IN_EMPTY_BLOCKS) {
                placeTemplate(level, targetPos, template);
            } else if (operation == ChunkPlaceOperation.SYNC_BLOCK_ENTITY_DATA) {
                syncTemplateData(level, targetPos, template);
            } else {
                destroyTemplate(level, targetPos, template);
            }
        }
    }

    private void placeTemplate(ServerLevel level, BlockPos targetPos, PlacedBlockTemplate template) {
        BlockState current = level.getBlockState(targetPos);
        if (!current.isAir()) {
            occupiedTargets++;
            return;
        }

        ChunkPlaceBlockManager.runInternalUpdate(() -> {
            if (level.setBlock(targetPos, template.state(), PLACE_FLAGS)) {
                blocksPlaced++;
                if (template.hasBlockEntityData() && applyBlockEntityTag(level, targetPos, template.state(), template.blockEntityTagFor(targetPos))) {
                    blockEntityCopies++;
                }
            }
        });
    }

    private void syncTemplateData(ServerLevel level, BlockPos targetPos, PlacedBlockTemplate template) {
        if (!template.hasBlockEntityData()) {
            return;
        }

        BlockState current = level.getBlockState(targetPos);
        if (current.getBlock() != template.state().getBlock() || !current.hasBlockEntity()) {
            mismatchedTargets++;
            return;
        }

        ChunkPlaceBlockManager.runInternalUpdate(() -> {
            if (applyBlockEntityTag(level, targetPos, current, template.blockEntityTagFor(targetPos))) {
                blockEntityCopies++;
            }
        });
    }

    private void destroyTemplate(ServerLevel level, BlockPos targetPos, PlacedBlockTemplate template) {
        BlockState current = level.getBlockState(targetPos);
        if (current.getBlock() != template.state().getBlock()) {
            mismatchedTargets++;
            return;
        }

        ChunkPlaceBlockManager.runInternalUpdate(() -> {
            if (level.setBlock(targetPos, Blocks.AIR.defaultBlockState(), SILENT_DESTROY_FLAGS)) {
                blocksDestroyed++;
            }
        });
    }

    static boolean applyBlockEntityTag(ServerLevel level, BlockPos targetPos, BlockState state, CompoundTag tag) {
        BlockEntity existing = level.getBlockEntity(targetPos);
        if (existing != null && existing.isValidBlockState(state)) {
            try {
                existing.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
                existing.setChanged();
                level.sendBlockUpdated(targetPos, state, state, DATA_UPDATE_FLAGS);
                return true;
            } catch (RuntimeException exception) {
                MiniGame.LOGGER.warn("Failed to load block entity data at {}", targetPos, exception);
                return false;
            }
        }

        BlockEntity loaded = BlockEntity.loadStatic(targetPos, state, tag, level.registryAccess());
        if (loaded == null) {
            return false;
        }

        level.setBlockEntity(loaded);
        loaded.setChanged();
        level.sendBlockUpdated(targetPos, state, state, DATA_UPDATE_FLAGS);
        return true;
    }

    private LevelChunk getChunk(ServerLevel level, int chunkX, int chunkZ) {
        LevelChunk loadedChunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (loadedChunk != null) {
            return loadedChunk;
        }

        if (settings.loadedChunksOnly()) {
            return null;
        }

        if (settings.allowChunkGeneration()) {
            return level.getChunk(chunkX, chunkZ);
        }

        ChunkAccess access = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        return access instanceof LevelChunk levelChunk ? levelChunk : null;
    }
}
