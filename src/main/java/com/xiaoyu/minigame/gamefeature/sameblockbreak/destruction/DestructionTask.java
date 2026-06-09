package com.xiaoyu.minigame.gamefeature.sameblockbreak.destruction;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.UUID;

import com.xiaoyu.minigame.gamefeature.common.world.ChunkLoading;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;

public final class DestructionTask {
    private static final int SILENT_REMOVE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_KNOWN_SHAPE;
    private static final int WATERLOGGED_UPDATE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private final ResourceKey<Level> dimension;
    private final UUID breakerId;
    private final TargetMatcher target;
    private final BlockPos center;
    private final long[] chunkQueue;
    private final DestructionSettings settings;
    private final long startedAtMillis = System.currentTimeMillis();

    private final BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();
    private final ArrayDeque<BlockPos> supportQueue = new ArrayDeque<>();
    private final HashSet<Long> queuedSupportChecks = new HashSet<>();

    private int chunkIndex;
    private int processedChunks;
    private int skippedChunks;
    private long blocksScanned;
    private long blocksDestroyed;
    private long naturalBreaks;
    private ChunkCursor currentCursor;
    private LevelChunk currentChunk;
    private boolean cancelled;
    private boolean completed;

    public DestructionTask(
            ResourceKey<Level> dimension,
            UUID breakerId,
            TargetMatcher target,
            BlockPos center,
            long[] chunkQueue,
            DestructionSettings settings
    ) {
        this.dimension = dimension;
        this.breakerId = breakerId;
        this.target = target;
        this.center = center.immutable();
        this.chunkQueue = chunkQueue;
        this.settings = settings;
    }

    public boolean tick(ServerLevel level, MinecraftServer server) {
        if (cancelled || completed) {
            return true;
        }

        long deadline = System.nanoTime() + settings.maxMsPerTick() * 1_000_000L;
        int chunksThisTick = 0;
        int blocksThisTick = 0;

        while (!completed && !cancelled) {
            if (System.nanoTime() >= deadline || blocksThisTick >= settings.blocksPerTick()) {
                return false;
            }

            if (!supportQueue.isEmpty()) {
                blocksThisTick += processSupportQueue(level, server, deadline, settings.blocksPerTick() - blocksThisTick);
                continue;
            }

            if (currentCursor == null) {
                if (chunksThisTick >= settings.chunksPerTick()) {
                    return false;
                }

                if (chunkIndex >= chunkQueue.length) {
                    completed = true;
                    return true;
                }

                long chunkKey = chunkQueue[chunkIndex++];
                chunksThisTick++;
                currentChunk = getChunk(level, ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
                if (currentChunk == null) {
                    skippedChunks++;
                    processedChunks++;
                    continue;
                }

                currentCursor = new ChunkCursor(currentChunk.getPos().x(), currentChunk.getPos().z());
            }

            int scannedThisStep = processCurrentChunkStep(level, server, currentChunk, deadline, settings.blocksPerChunkStep(), settings.blocksPerTick() - blocksThisTick);
            blocksThisTick += scannedThisStep;

            if (currentCursor != null && currentCursor.isComplete()) {
                currentCursor = null;
                currentChunk = null;
                processedChunks++;
            }

            if (scannedThisStep == 0 && currentCursor != null) {
                return false;
            }
        }

        return true;
    }

    public void cancel() {
        this.cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public boolean isCompleted() {
        return completed;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public String targetDescription() {
        return target.description();
    }

    public Component targetDisplayName() {
        return target.displayName();
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

    public long blocksScanned() {
        return blocksScanned;
    }

    public long blocksDestroyed() {
        return blocksDestroyed;
    }

    public long naturalBreaks() {
        return naturalBreaks;
    }

    public long elapsedMillis() {
        return System.currentTimeMillis() - startedAtMillis;
    }

    public double progress() {
        return chunkQueue.length == 0 ? 1.0D : Math.min(1.0D, processedChunks / (double) chunkQueue.length);
    }

    public String summary() {
        return String.format(
                "%s %.1f%% chunks=%d/%d destroyed=%d scanned=%d skipped=%d",
                targetDescription(),
                progress() * 100.0D,
                processedChunks,
                chunkQueue.length,
                blocksDestroyed,
                blocksScanned,
                skippedChunks
        );
    }

    public Component summaryComponent() {
        MutableComponent progress = Component.literal(String.format("%.1f", progress() * 100.0D));
        return Component.translatable(
                "minigame.sameblockbreak.status.task",
                targetDisplayName(),
                progress,
                processedChunks,
                chunkQueue.length,
                blocksDestroyed,
                blocksScanned,
                skippedChunks
        );
    }

    private int processCurrentChunkStep(ServerLevel level, MinecraftServer server, LevelChunk chunk, long deadline, int maxStepBlocks, int remainingTickBlocks) {
        int scanned = 0;
        int limit = Math.min(maxStepBlocks, remainingTickBlocks);
        while (currentCursor != null && scanned < limit && System.nanoTime() < deadline) {
            BlockPos.MutableBlockPos pos = currentCursor.next(chunk, scanPos);
            if (pos == null) {
                break;
            }

            scanned++;
            blocksScanned++;

            if (isTriggerPos(pos)) {
                continue;
            }

            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }

            if (target.shouldDryWaterlogged(state)) {
                BlockPos immutablePos = pos.immutable();
                BlockState dry = state.setValue(BlockStateProperties.WATERLOGGED, false);
                level.setBlock(immutablePos, dry, WATERLOGGED_UPDATE_FLAGS);
                blocksDestroyed++;
                continue;
            }

            if (!target.matches(state)) {
                continue;
            }

            destroyMatchedBlock(level, server, pos, state);
        }
        return scanned;
    }

    private void destroyMatchedBlock(ServerLevel level, MinecraftServer server, BlockPos pos, BlockState state) {
        BlockPos immutablePos = pos.immutable();
        boolean fluid = target.isTargetFluid(state);
        boolean withinDropRadius = Math.abs(immutablePos.getX() - center.getX()) <= settings.dropRadius()
                && Math.abs(immutablePos.getZ() - center.getZ()) <= settings.dropRadius();
        boolean natural = !fluid
                && withinDropRadius
                && (settings.maxNaturalBreakBlocks() < 0 || naturalBreaks < settings.maxNaturalBreakBlocks());

        if (natural) {
            Entity breaker = breaker(level);
            DestructionManager.runInternalBreak(() -> level.destroyBlock(immutablePos, true, breaker, Block.UPDATE_LIMIT));
            naturalBreaks++;
        } else {
            DestructionManager.runInternalBreak(() -> level.setBlock(immutablePos, Blocks.AIR.defaultBlockState(), SILENT_REMOVE_FLAGS));
        }

        blocksDestroyed++;
        queueSupportChecks(level, immutablePos);
    }

    private int processSupportQueue(ServerLevel level, MinecraftServer server, long deadline, int remainingTickBlocks) {
        int processed = 0;
        while (processed < remainingTickBlocks && System.nanoTime() < deadline) {
            BlockPos pos = supportQueue.pollFirst();
            if (pos == null) {
                queuedSupportChecks.clear();
                break;
            }

            queuedSupportChecks.remove(pos.asLong());
            processed++;
            blocksScanned++;

            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.canSurvive(level, pos)) {
                continue;
            }

            destroyUnsupportedBlock(level, server, pos, state);
        }
        return processed;
    }

    private void destroyUnsupportedBlock(ServerLevel level, MinecraftServer server, BlockPos pos, BlockState state) {
        boolean withinDropRadius = Math.abs(pos.getX() - center.getX()) <= settings.dropRadius()
                && Math.abs(pos.getZ() - center.getZ()) <= settings.dropRadius();
        boolean natural = withinDropRadius
                && !target.isTargetFluid(state)
                && (settings.maxNaturalBreakBlocks() < 0 || naturalBreaks < settings.maxNaturalBreakBlocks());

        if (natural) {
            Entity breaker = breaker(level);
            DestructionManager.runInternalBreak(() -> level.destroyBlock(pos, true, breaker, Block.UPDATE_LIMIT));
            naturalBreaks++;
        } else {
            DestructionManager.runInternalBreak(() -> level.setBlock(pos, Blocks.AIR.defaultBlockState(), SILENT_REMOVE_FLAGS));
        }

        blocksDestroyed++;
        queueSupportChecks(level, pos);
    }

    private void queueSupportChecks(ServerLevel level, BlockPos sourcePos) {
        if (!settings.cleanupUnsupportedNeighbors()) {
            return;
        }

        for (Direction direction : Direction.values()) {
            BlockPos neighbor = sourcePos.relative(direction);
            if (!level.isInWorldBounds(neighbor)) {
                continue;
            }

            long key = neighbor.asLong();
            if (queuedSupportChecks.add(key)) {
                supportQueue.addLast(neighbor);
            }
        }
    }

    private LevelChunk getChunk(ServerLevel level, int chunkX, int chunkZ) {
        return ChunkLoading.getProcessableChunk(
                level,
                chunkX,
                chunkZ,
                settings.loadedChunksOnly(),
                settings.allowChunkGeneration()
        );
    }

    private boolean isTriggerPos(BlockPos pos) {
        return pos.getX() == center.getX() && pos.getY() == center.getY() && pos.getZ() == center.getZ();
    }

    private Entity breaker(ServerLevel level) {
        return breakerId == null ? null : level.getEntity(breakerId);
    }
}
