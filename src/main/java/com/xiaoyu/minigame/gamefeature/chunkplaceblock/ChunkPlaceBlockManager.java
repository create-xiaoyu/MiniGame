package com.xiaoyu.minigame.gamefeature.chunkplaceblock;

import com.mojang.logging.LogUtils;
import com.xiaoyu.minigame.gamefeature.chunkplaceblock.ChunkPlaceBlockSavedData.SavedPlacementRule;
import com.xiaoyu.minigame.gamefeature.chunkplaceblock.config.ChunkPlaceBlockConfig;
import com.xiaoyu.minigame.gamefeature.common.chunk.ChunkTracker;
import com.xiaoyu.minigame.gamefeature.sameblockbreak.SameBlockBreakManager;
import com.xiaoyu.minigame.gamefeature.sameblockbreak.config.SameBlockBreakConfig;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueInput;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ChunkPlaceBlockManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ServerLevel, LevelState> LEVEL_STATES = new IdentityHashMap<>();
    private static final ThreadLocal<Integer> SYNC_DEPTH = ThreadLocal.withInitial(() -> 0);

    private ChunkPlaceBlockManager() {
    }

    public static void syncFromPlacement(ServerLevel level, List<BlockSnapshot> snapshots, @Nullable Entity sourceEntity, boolean multiBlock) {
        if (!ChunkPlaceBlockConfig.ENABLED.get()
                || !ChunkPlaceBlockConfig.SYNC_BLOCK_PLACEMENTS.get()
                || isSyncing()
                || snapshots.isEmpty()) {
            return;
        }

        if (multiBlock && !ChunkPlaceBlockConfig.SYNC_MULTI_BLOCK_PLACEMENTS.get()) {
            LOGGER.debug("Chunk-place skipped multi-block placement because syncMultiBlockPlacements is disabled");
            return;
        }

        List<MirroredBlock> blocks = new ArrayList<>();
        for (BlockSnapshot snapshot : snapshots) {
            MirroredBlock block = captureMirroredBlock(level, snapshot.getPos());
            if (block != null) {
                blocks.add(block);
            }
        }

        if (blocks.isEmpty()) {
            LOGGER.debug("Chunk-place placement event had no non-air final blocks to mirror");
            return;
        }

        LOGGER.debug(
                "Chunk-place placement trigger in {}: blocks={}, source={}",
                level.dimension(),
                blocks.size(),
                sourceEntity == null ? "none" : sourceEntity.getScoreboardName()
        );

        PlacementResult result = applyPlacementToLoadedChunks(
                level,
                blocks,
                ChunkPlaceBlockConfig.MAX_PLACEMENTS_PER_TRIGGER.getAsInt(),
                "trigger"
        );
        int persistedRules = upsertPersistentRules(level, blocks);

        LOGGER.debug(
                "Chunk-place placement result in {}: changed={}, chunksVisited={}, occupiedSkipped={}, unsupportedSkipped={}, outOfBoundsSkipped={}, sourceSkipped={}, staleChunkSkipped={}, limited={}, persistedRulesChanged={}",
                level.dimension(),
                result.changed,
                result.chunksVisited,
                result.skippedOccupied,
                result.skippedUnsupported,
                result.skippedOutOfBounds,
                result.skippedSource,
                result.skippedStaleChunks,
                result.limited,
                persistedRules
        );

        if (sourceEntity instanceof ServerPlayer player && ChunkPlaceBlockConfig.SEND_PLACEMENT_MESSAGE.get()) {
            player.sendSystemMessage(Component.translatable(
                    result.limited ? "message.minigame.chunkplaceblock.placed_limited" : "message.minigame.chunkplaceblock.placed",
                    blocks.getFirst().state().getBlock().getName(),
                    result.changed,
                    result.chunksVisited
            ));
        }
    }

    public static void captureBucketPlacementCandidate(ServerLevel level, Player player, ItemStack stack, BlockPos clickedPos, Direction face) {
        if (!ChunkPlaceBlockConfig.ENABLED.get()
                || !ChunkPlaceBlockConfig.SYNC_BUCKET_FLUID_PLACEMENTS.get()
                || isSyncing()
                || !(stack.getItem() instanceof BucketItem bucketItem)) {
            return;
        }

        Fluid content = bucketItem.getContent();
        if (content == Fluids.EMPTY || !(content instanceof FlowingFluid)) {
            return;
        }

        BlockState clickedState = level.getBlockState(clickedPos);
        boolean containerPlacement = content == Fluids.WATER && canBlockContainFluid(player, level, clickedPos, clickedState, content);
        if (containerPlacement && !ChunkPlaceBlockConfig.SYNC_LIQUID_CONTAINER_FLUID_PLACEMENTS.get()) {
            LOGGER.debug("Chunk-place skipped bucket candidate at {} because it targets a liquid container", clickedPos);
            return;
        }

        BlockPos placePos = containerPlacement ? clickedPos : clickedPos.relative(face);
        if (!level.isInWorldBounds(placePos)) {
            LOGGER.debug("Chunk-place skipped bucket candidate outside world bounds: {}", placePos);
            return;
        }

        stateFor(level).addPendingBucket(new PendingBucketPlacement(
                placePos.immutable(),
                sourceFluid(content),
                level.getGameTime(),
                player.getUUID(),
                containerPlacement
        ));
        LOGGER.debug(
                "Chunk-place recorded bucket candidate in {}: pos={}, fluid={}, player={}, containerPlacement={}",
                level.dimension(),
                placePos,
                content,
                player.getScoreboardName(),
                containerPlacement
        );
    }

    public static void syncFromBreak(ServerLevel level, BlockPos origin, BlockState state, @Nullable Entity breaker) {
        if (!ChunkPlaceBlockConfig.ENABLED.get()
                || !ChunkPlaceBlockConfig.SYNC_BREAKS.get()
                || isSyncing()
                || state.isAir()) {
            return;
        }

        int removedRules = removePersistentRulesForBreak(level, origin, state);
        if (ChunkPlaceBlockConfig.SYNC_BREAKS_ONLY_WHEN_SAME_BLOCK_BREAK_DISABLED.get() && SameBlockBreakConfig.ENABLED.get()) {
            LOGGER.debug(
                    "Chunk-place removed {} persistent rule(s) for {}, but skipped mirrored breaking because sameblockbreak is enabled",
                    removedRules,
                    origin
            );
            return;
        }

        BreakResult result = applyBreakToLoadedChunks(level, origin, state, breaker);
        LOGGER.debug(
                "Chunk-place break result in {}: changed={}, chunksVisited={}, nonMatchingSkipped={}, outOfBoundsSkipped={}, sourceSkipped={}, staleChunkSkipped={}, limited={}, persistentRulesRemoved={}",
                level.dimension(),
                result.changed,
                result.chunksVisited,
                result.skippedNonMatching,
                result.skippedOutOfBounds,
                result.skippedSource,
                result.skippedStaleChunks,
                result.limited,
                removedRules
        );

        if (breaker instanceof ServerPlayer player && ChunkPlaceBlockConfig.SEND_BREAK_MESSAGE.get()) {
            player.sendSystemMessage(Component.translatable(
                    result.limited ? "message.minigame.chunkplaceblock.broken_limited" : "message.minigame.chunkplaceblock.broken",
                    state.getBlock().getName(),
                    result.changed,
                    result.chunksVisited
            ));
        }
    }

    public static void tick(ServerLevel level) {
        if (!ChunkPlaceBlockConfig.ENABLED.get()) {
            return;
        }

        LevelState levelState = LEVEL_STATES.get(level);
        if (levelState != null) {
            levelState.processPendingBuckets(level);
        }

        if (!ChunkPlaceBlockConfig.PERSIST_PLACEMENT_RULES.get()) {
            return;
        }

        ChunkPlaceBlockSavedData data = existingSavedData(level);
        if (data == null || data.ruleCount() == 0) {
            if (levelState != null) {
                levelState.clearPersistentPlacementPausedLog();
            }
            return;
        }

        LevelState persistentLevelState = stateFor(level);
        if (isPersistentPlacementDisabledBySameBlockBreak()) {
            persistentLevelState.logPersistentPlacementPaused(level, data.ruleCount());
            return;
        }

        persistentLevelState.clearPersistentPlacementPausedLog();
        persistentLevelState.processPersistentRules(level, data.rules());
    }

    public static void onChunkUnload(ServerLevel level, long chunkKey) {
        LevelState levelState = LEVEL_STATES.get(level);
        if (levelState != null) {
            levelState.processedPersistentChunks.remove(chunkKey);
        }
    }

    public static void onLevelUnload(ServerLevel level) {
        LEVEL_STATES.remove(level);
    }

    public static int clearRules(ServerLevel level) {
        ChunkPlaceBlockSavedData data = existingSavedData(level);
        int removed = data == null ? 0 : data.clearRules();
        LevelState levelState = LEVEL_STATES.get(level);
        if (levelState != null) {
            levelState.invalidatePersistentChunks();
        }
        LOGGER.debug("Chunk-place cleared {} persistent placement rule(s) in {}", removed, level.dimension());
        return removed;
    }

    public static ChunkPlaceBlockStatus status(ServerLevel level) {
        LevelState levelState = LEVEL_STATES.get(level);
        ChunkPlaceBlockSavedData data = existingSavedData(level);
        int pendingBuckets = levelState == null ? 0 : levelState.pendingBuckets.size();
        int processedChunks = levelState == null ? 0 : levelState.processedPersistentChunks.size();
        int persistentRules = data == null ? 0 : data.ruleCount();
        int loadedChunks = ChunkTracker.getLoadedChunks(level).size();
        return new ChunkPlaceBlockStatus(loadedChunks, persistentRules, processedChunks, pendingBuckets);
    }

    public static void clearAll() {
        LEVEL_STATES.clear();
        SYNC_DEPTH.remove();
    }

    private static LevelState stateFor(ServerLevel level) {
        return LEVEL_STATES.computeIfAbsent(level, ignored -> new LevelState());
    }

    private static @Nullable ChunkPlaceBlockSavedData existingSavedData(ServerLevel level) {
        return level.getDataStorage().get(ChunkPlaceBlockSavedData.TYPE);
    }

    private static ChunkPlaceBlockSavedData savedData(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(ChunkPlaceBlockSavedData.TYPE);
    }

    private static @Nullable MirroredBlock captureMirroredBlock(ServerLevel level, BlockPos sourcePos) {
        if (!level.isInWorldBounds(sourcePos)) {
            return null;
        }

        BlockState state = level.getBlockState(sourcePos);
        if (state.isAir()) {
            return null;
        }

        CompoundTag blockEntityTag = null;
        if (ChunkPlaceBlockConfig.COPY_BLOCK_ENTITY_DATA.get() && state.hasBlockEntity()) {
            BlockEntity blockEntity = level.getBlockEntity(sourcePos);
            if (blockEntity != null) {
                blockEntityTag = blockEntity.saveWithFullMetadata(level.registryAccess());
            }
        }

        return MirroredBlock.fromSource(sourcePos.immutable(), state, blockEntityTag);
    }

    private static int upsertPersistentRules(ServerLevel level, List<MirroredBlock> blocks) {
        if (!ChunkPlaceBlockConfig.PERSIST_PLACEMENT_RULES.get()) {
            return 0;
        }
        if (isPersistentPlacementDisabledBySameBlockBreak()) {
            LOGGER.debug(
                    "Chunk-place skipped storing {} persistent placement rule(s) in {} because sameblockbreak is enabled",
                    blocks.size(),
                    level.dimension()
            );
            return 0;
        }

        ChunkPlaceBlockSavedData data = savedData(level);
        int changed = 0;
        for (MirroredBlock block : blocks) {
            if (data.upsertRule(block.toSavedRule())) {
                changed++;
            }
        }

        if (changed > 0) {
            stateFor(level).invalidatePersistentChunks();
        }
        return changed;
    }

    private static int removePersistentRulesForBreak(ServerLevel level, BlockPos origin, BlockState state) {
        if (!ChunkPlaceBlockConfig.PERSIST_PLACEMENT_RULES.get()) {
            return 0;
        }

        ChunkPlaceBlockSavedData data = existingSavedData(level);
        if (data == null) {
            return 0;
        }

        int removed = data.removeRulesForBreak(
                origin.getX() & 15,
                origin.getY(),
                origin.getZ() & 15,
                state,
                ChunkPlaceBlockConfig.BREAK_ONLY_MATCHING_BLOCK.get(),
                ChunkPlaceBlockConfig.BREAK_REQUIRE_EXACT_STATE.get()
        );
        if (removed > 0) {
            stateFor(level).invalidatePersistentChunks();
        }
        return removed;
    }

    private static PlacementResult applyPlacementToLoadedChunks(ServerLevel level, List<MirroredBlock> blocks, int maxChanges, String reason) {
        PlacementResult result = new PlacementResult();
        MutableBudget budget = new MutableBudget(maxChanges);
        LongSet sourcePositions = sourcePositions(blocks);
        Collection<LevelChunk> loadedChunks = ChunkTracker.getLoadedChunks(level);

        for (LevelChunk chunk : loadedChunks) {
            if (!budget.hasBudget()) {
                result.limited = true;
                break;
            }

            if (!isCurrentLoadedChunk(level, chunk)) {
                result.skippedStaleChunks++;
                continue;
            }

            result.chunksVisited++;
            applyPlacementToChunk(level, chunk, blocks, sourcePositions, budget, result, reason);
        }

        return result;
    }

    private static void applyPlacementToChunk(
            ServerLevel level,
            LevelChunk chunk,
            List<MirroredBlock> blocks,
            LongSet sourcePositions,
            MutableBudget budget,
            PlacementResult result,
            String reason
    ) {
        if (ChunkPlaceBlockConfig.REQUIRE_ALL_TARGETS_EMPTY_FOR_MULTI_BLOCK.get() && blocks.size() > 1) {
            int neededChanges = 0;
            for (MirroredBlock block : blocks) {
                BlockPos targetPos = block.targetPos(chunk);
                PlacementSkipReason skipReason = placementSkipReason(level, chunk, targetPos, block, sourcePositions);
                if (skipReason == PlacementSkipReason.SOURCE) {
                    continue;
                }
                if (skipReason != PlacementSkipReason.NONE) {
                    result.addPlacementSkip(skipReason);
                    logSkippedTarget("placement", reason, targetPos, skipReason);
                    return;
                }
                neededChanges++;
            }

            if (!budget.canSpend(neededChanges)) {
                result.limited = true;
                return;
            }
        }

        for (MirroredBlock block : blocks) {
            if (!budget.hasBudget()) {
                result.limited = true;
                return;
            }

            BlockPos targetPos = block.targetPos(chunk);
            PlacementSkipReason skipReason = placementSkipReason(level, chunk, targetPos, block, sourcePositions);
            if (skipReason != PlacementSkipReason.NONE) {
                result.addPlacementSkip(skipReason);
                logSkippedTarget("placement", reason, targetPos, skipReason);
                continue;
            }

            if (placeMirroredBlock(level, chunk, targetPos, block)) {
                budget.spendOne();
                result.changed++;
            }
        }
    }

    private static BreakResult applyBreakToLoadedChunks(ServerLevel level, BlockPos origin, BlockState state, @Nullable Entity breaker) {
        BreakResult result = new BreakResult();
        MutableBudget budget = new MutableBudget(ChunkPlaceBlockConfig.MAX_BREAKS_PER_TRIGGER.getAsInt());
        int localX = origin.getX() & 15;
        int localZ = origin.getZ() & 15;

        for (LevelChunk chunk : ChunkTracker.getLoadedChunks(level)) {
            if (!budget.hasBudget()) {
                result.limited = true;
                break;
            }

            if (!isCurrentLoadedChunk(level, chunk)) {
                result.skippedStaleChunks++;
                continue;
            }

            result.chunksVisited++;
            BlockPos targetPos = new BlockPos(
                    SectionPos.sectionToBlockCoord(chunk.getPos().x(), localX),
                    origin.getY(),
                    SectionPos.sectionToBlockCoord(chunk.getPos().z(), localZ)
            );

            if (targetPos.asLong() == origin.asLong()) {
                result.skippedSource++;
                continue;
            }
            if (!level.isInWorldBounds(targetPos)) {
                result.skippedOutOfBounds++;
                logSkippedTarget("break", "trigger", targetPos, PlacementSkipReason.OUT_OF_BOUNDS);
                continue;
            }

            BlockState targetState = chunk.getBlockState(targetPos);
            if (targetState.isAir()) {
                result.skippedAir++;
                continue;
            }
            if (ChunkPlaceBlockConfig.BREAK_ONLY_MATCHING_BLOCK.get() && !matchesBreakTarget(state, targetState)) {
                result.skippedNonMatching++;
                logSkippedTarget("break", "trigger", targetPos, PlacementSkipReason.NON_MATCHING);
                continue;
            }

            if (destroyMirroredBlock(level, chunk, targetPos, targetState, breaker)) {
                budget.spendOne();
                result.changed++;
            }
        }

        return result;
    }

    private static PlacementSkipReason placementSkipReason(ServerLevel level, LevelChunk chunk, BlockPos targetPos, MirroredBlock block, LongSet sourcePositions) {
        if (sourcePositions.contains(targetPos.asLong())) {
            return PlacementSkipReason.SOURCE;
        }
        if (!level.isInWorldBounds(targetPos)) {
            return PlacementSkipReason.OUT_OF_BOUNDS;
        }
        if (block.state().isAir()) {
            return PlacementSkipReason.AIR_SOURCE;
        }

        BlockState currentState = chunk.getBlockState(targetPos);
        if (ChunkPlaceBlockConfig.ONLY_PLACE_IN_AIR.get()) {
            if (!currentState.isAir()) {
                return PlacementSkipReason.OCCUPIED;
            }
        } else if (!currentState.isAir() && !currentState.canBeReplaced()) {
            return PlacementSkipReason.OCCUPIED;
        }

        if (ChunkPlaceBlockConfig.CHECK_PLACEMENT_SURVIVAL.get() && !block.state().canSurvive(level, targetPos)) {
            return PlacementSkipReason.UNSUPPORTED;
        }

        return PlacementSkipReason.NONE;
    }

    private static boolean placeMirroredBlock(ServerLevel level, LevelChunk chunk, BlockPos targetPos, MirroredBlock block) {
        if (!isCurrentLoadedChunk(level, chunk)) {
            return false;
        }

        beginSync();
        SameBlockBreakManager.beginEntityPlacementBypass();
        try {
            boolean changed = level.setBlock(
                    targetPos,
                    block.state(),
                    effectivePlacementUpdateFlags(),
                    effectivePlacementUpdateLimit()
            );
            if (changed) {
                restoreBlockEntityData(level, targetPos, block);
            }
            return changed;
        } finally {
            SameBlockBreakManager.endEntityPlacementBypass();
            endSync();
        }
    }

    private static boolean destroyMirroredBlock(ServerLevel level, LevelChunk chunk, BlockPos targetPos, BlockState targetState, @Nullable Entity breaker) {
        if (!isCurrentLoadedChunk(level, chunk)) {
            return false;
        }

        beginSync();
        try {
            if (ChunkPlaceBlockConfig.BREAK_DROP_RESOURCES.get()) {
                return level.destroyBlock(
                    targetPos,
                    true,
                    breaker,
                    effectiveBreakUpdateLimit()
                );
            }

            return level.setBlock(
                    targetPos,
                    targetState.getFluidState().createLegacyBlock(),
                    effectiveBreakUpdateFlags(),
                    effectiveBreakUpdateLimit()
            );
        } finally {
            endSync();
        }
    }

    private static void restoreBlockEntityData(ServerLevel level, BlockPos targetPos, MirroredBlock block) {
        if (!ChunkPlaceBlockConfig.COPY_BLOCK_ENTITY_DATA.get() || block.blockEntityTag() == null || !block.state().hasBlockEntity()) {
            return;
        }

        BlockEntity blockEntity = level.getBlockEntity(targetPos);
        if (blockEntity == null) {
            LOGGER.debug("Chunk-place could not restore block entity data at {} because no block entity exists after placement", targetPos);
            return;
        }

        CompoundTag tag = block.blockEntityTag().copy();
        tag.putInt("x", targetPos.getX());
        tag.putInt("y", targetPos.getY());
        tag.putInt("z", targetPos.getZ());

        try (ProblemReporter.ScopedCollector problems = new ProblemReporter.ScopedCollector(blockEntity.problemPath(), LOGGER)) {
            blockEntity.loadWithComponents(TagValueInput.create(problems, level.registryAccess(), tag));
            blockEntity.setChanged();
        } catch (RuntimeException exception) {
            LOGGER.warn("Chunk-place failed to restore block entity data at {}", targetPos, exception);
        }
    }

    private static boolean matchesBreakTarget(BlockState sourceState, BlockState targetState) {
        if (ChunkPlaceBlockConfig.BREAK_REQUIRE_EXACT_STATE.get()) {
            return sourceState == targetState;
        }
        return targetState.is(sourceState.getBlock());
    }

    private static boolean canBlockContainFluid(Player player, ServerLevel level, BlockPos pos, BlockState state, Fluid fluid) {
        return state.getBlock() instanceof LiquidBlockContainer container && container.canPlaceLiquid(player, level, pos, state, fluid);
    }

    private static int effectivePlacementUpdateFlags() {
        return effectiveLocalChunkUpdateFlags(ChunkPlaceBlockConfig.PLACEMENT_UPDATE_FLAGS.getAsInt());
    }

    private static int effectivePlacementUpdateLimit() {
        return effectiveUpdateLimit(ChunkPlaceBlockConfig.PLACEMENT_UPDATE_LIMIT.getAsInt());
    }

    private static int effectiveBreakUpdateFlags() {
        return effectiveLocalChunkUpdateFlags(ChunkPlaceBlockConfig.BREAK_UPDATE_FLAGS.getAsInt());
    }

    private static int effectiveBreakUpdateLimit() {
        return effectiveUpdateLimit(ChunkPlaceBlockConfig.BREAK_UPDATE_LIMIT.getAsInt());
    }

    private static int effectiveLocalChunkUpdateFlags(int configuredFlags) {
        if (!ChunkPlaceBlockConfig.PREVENT_NEIGHBOR_CHUNK_LOADING.get()) {
            return configuredFlags;
        }

        return configuredFlags
                & ~Block.UPDATE_NEIGHBORS
                | Block.UPDATE_CLIENTS
                | Block.UPDATE_KNOWN_SHAPE
                | Block.UPDATE_SKIP_ON_PLACE;
    }

    private static int effectiveUpdateLimit(int configuredLimit) {
        return ChunkPlaceBlockConfig.PREVENT_NEIGHBOR_CHUNK_LOADING.get() ? 0 : configuredLimit;
    }

    private static boolean isPersistentPlacementDisabledBySameBlockBreak() {
        return ChunkPlaceBlockConfig.DISABLE_PERSISTENT_PLACEMENT_WHEN_SAME_BLOCK_BREAK_ENABLED.get()
                && SameBlockBreakConfig.ENABLED.get();
    }

    private static boolean isCurrentLoadedChunk(ServerLevel level, LevelChunk chunk) {
        return level.getChunkSource().getChunkNow(chunk.getPos().x(), chunk.getPos().z()) == chunk;
    }

    private static boolean isBucketCandidatePlaced(ServerLevel level, PendingBucketPlacement candidate, BlockState state) {
        FluidState fluidState = state.getFluidState();
        if (fluidState.isEmpty() || !sourceFluid(fluidState.getType()).isSame(candidate.fluid())) {
            return false;
        }

        return candidate.containerPlacement() || state.getBlock() instanceof LiquidBlock;
    }

    private static Fluid sourceFluid(Fluid fluid) {
        return fluid instanceof FlowingFluid flowingFluid ? flowingFluid.getSource() : fluid;
    }

    private static LongSet sourcePositions(List<MirroredBlock> blocks) {
        LongOpenHashSet sourcePositions = new LongOpenHashSet();
        for (MirroredBlock block : blocks) {
            if (block.sourcePos() != null) {
                sourcePositions.add(block.sourcePos().asLong());
            }
        }
        return sourcePositions;
    }

    private static boolean isSyncing() {
        return SYNC_DEPTH.get() > 0;
    }

    private static void beginSync() {
        SYNC_DEPTH.set(SYNC_DEPTH.get() + 1);
    }

    private static void endSync() {
        int depth = SYNC_DEPTH.get() - 1;
        if (depth <= 0) {
            SYNC_DEPTH.remove();
        } else {
            SYNC_DEPTH.set(depth);
        }
    }

    private static void logSkippedTarget(String operation, String reason, BlockPos targetPos, PlacementSkipReason skipReason) {
        if (ChunkPlaceBlockConfig.DEBUG_LOG_SKIPPED_TARGETS.get()) {
            LOGGER.debug("Chunk-place skipped {} target during {}: pos={}, reason={}", operation, reason, targetPos, skipReason);
        }
    }

    public record ChunkPlaceBlockStatus(int loadedChunks, int persistentRules, int processedPersistentChunks, int pendingBucketPlacements) {
    }

    private static final class LevelState {
        private final List<PendingBucketPlacement> pendingBuckets = new ArrayList<>();
        private final LongSet processedPersistentChunks = new LongOpenHashSet();
        private boolean loggedPersistentPlacementPaused;

        private void addPendingBucket(PendingBucketPlacement candidate) {
            this.pendingBuckets.removeIf(existing -> existing.sameTarget(candidate));
            this.pendingBuckets.add(candidate);
        }

        private void processPendingBuckets(ServerLevel level) {
            if (this.pendingBuckets.isEmpty()) {
                return;
            }

            long now = level.getGameTime();
            Iterator<PendingBucketPlacement> iterator = this.pendingBuckets.iterator();
            while (iterator.hasNext()) {
                PendingBucketPlacement candidate = iterator.next();
                BlockState state = level.getBlockState(candidate.pos());
                if (isBucketCandidatePlaced(level, candidate, state)) {
                    MirroredBlock block = captureMirroredBlock(level, candidate.pos());
                    iterator.remove();
                    if (block != null) {
                        ServerPlayer player = level.getServer().getPlayerList().getPlayer(candidate.playerId());
                        LOGGER.debug(
                                "Chunk-place confirmed bucket placement in {}: pos={}, state={}, player={}",
                                level.dimension(),
                                candidate.pos(),
                                state,
                                player == null ? candidate.playerId() : player.getScoreboardName()
                        );
                        syncBucketPlacement(level, block, player);
                    }
                    continue;
                }

                if (now - candidate.createdTick() > ChunkPlaceBlockConfig.MAX_PENDING_BUCKET_TICKS.getAsInt()) {
                    LOGGER.debug("Chunk-place discarded stale bucket candidate in {} at {}", level.dimension(), candidate.pos());
                    iterator.remove();
                }
            }
        }

        private void processPersistentRules(ServerLevel level, List<SavedPlacementRule> savedRules) {
            if (savedRules.isEmpty()) {
                return;
            }

            List<MirroredBlock> rules = savedRules.stream().map(MirroredBlock::fromSavedRule).toList();
            MutableBudget placementBudget = new MutableBudget(ChunkPlaceBlockConfig.MAX_PERSISTENT_PLACEMENTS_PER_TICK.getAsInt());
            MutableBudget chunkBudget = new MutableBudget(ChunkPlaceBlockConfig.MAX_PERSISTENT_CHUNKS_PER_TICK.getAsInt());
            PlacementResult result = new PlacementResult();
            LongSet emptySources = new LongOpenHashSet();

            for (LevelChunk chunk : ChunkTracker.getLoadedChunks(level)) {
                long chunkKey = chunk.getPos().pack();
                if (this.processedPersistentChunks.contains(chunkKey)) {
                    continue;
                }
                if (!chunkBudget.hasBudget() || !placementBudget.hasBudget()) {
                    result.limited = true;
                    break;
                }
                if (!isCurrentLoadedChunk(level, chunk)) {
                    result.skippedStaleChunks++;
                    continue;
                }

                result.chunksVisited++;
                applyPlacementToChunk(level, chunk, rules, emptySources, placementBudget, result, "persistent");
                if (result.limited) {
                    break;
                }

                this.processedPersistentChunks.add(chunkKey);
                chunkBudget.spendOne();
            }

            if (result.changed > 0 || result.limited) {
                LOGGER.debug(
                        "Chunk-place persistent pass in {}: changed={}, chunksVisited={}, processedChunks={}, staleChunkSkipped={}, rules={}, limited={}",
                        level.dimension(),
                        result.changed,
                        result.chunksVisited,
                        this.processedPersistentChunks.size(),
                        result.skippedStaleChunks,
                        savedRules.size(),
                        result.limited
                );
            }
        }

        private void logPersistentPlacementPaused(ServerLevel level, int ruleCount) {
            if (this.loggedPersistentPlacementPaused) {
                return;
            }

            LOGGER.debug(
                    "Chunk-place paused persistent placement catch-up in {} because sameblockbreak is enabled (rules={})",
                    level.dimension(),
                    ruleCount
            );
            this.loggedPersistentPlacementPaused = true;
        }

        private void clearPersistentPlacementPausedLog() {
            this.loggedPersistentPlacementPaused = false;
        }

        private void invalidatePersistentChunks() {
            this.processedPersistentChunks.clear();
            this.loggedPersistentPlacementPaused = false;
        }
    }

    private static void syncBucketPlacement(ServerLevel level, MirroredBlock block, @Nullable ServerPlayer player) {
        List<MirroredBlock> blocks = List.of(block);
        PlacementResult result = applyPlacementToLoadedChunks(
                level,
                blocks,
                ChunkPlaceBlockConfig.MAX_PLACEMENTS_PER_TRIGGER.getAsInt(),
                "bucket"
        );
        int persistedRules = upsertPersistentRules(level, blocks);
        LOGGER.debug(
                "Chunk-place bucket placement result in {}: changed={}, chunksVisited={}, staleChunkSkipped={}, limited={}, persistedRulesChanged={}",
                level.dimension(),
                result.changed,
                result.chunksVisited,
                result.skippedStaleChunks,
                result.limited,
                persistedRules
        );

        if (player != null && ChunkPlaceBlockConfig.SEND_PLACEMENT_MESSAGE.get()) {
            player.sendSystemMessage(Component.translatable(
                    result.limited ? "message.minigame.chunkplaceblock.placed_limited" : "message.minigame.chunkplaceblock.placed",
                    block.state().getBlock().getName(),
                    result.changed,
                    result.chunksVisited
            ));
        }
    }

    private record PendingBucketPlacement(BlockPos pos, Fluid fluid, long createdTick, UUID playerId, boolean containerPlacement) {
        private boolean sameTarget(PendingBucketPlacement other) {
            return this.pos.equals(other.pos) && this.playerId.equals(other.playerId);
        }
    }

    private record MirroredBlock(
            @Nullable BlockPos sourcePos,
            int localX,
            int y,
            int localZ,
            BlockState state,
            @Nullable CompoundTag blockEntityTag
    ) {
        private MirroredBlock {
            localX &= 15;
            localZ &= 15;
            blockEntityTag = blockEntityTag == null ? null : blockEntityTag.copy();
        }

        private static MirroredBlock fromSource(BlockPos sourcePos, BlockState state, @Nullable CompoundTag blockEntityTag) {
            return new MirroredBlock(sourcePos, sourcePos.getX() & 15, sourcePos.getY(), sourcePos.getZ() & 15, state, blockEntityTag);
        }

        private static MirroredBlock fromSavedRule(SavedPlacementRule rule) {
            return new MirroredBlock(null, rule.localX(), rule.y(), rule.localZ(), rule.state(), rule.blockEntityTag());
        }

        private SavedPlacementRule toSavedRule() {
            return new SavedPlacementRule(this.localX, this.y, this.localZ, this.state, this.blockEntityTag);
        }

        private BlockPos targetPos(LevelChunk chunk) {
            return new BlockPos(
                    SectionPos.sectionToBlockCoord(chunk.getPos().x(), this.localX),
                    this.y,
                    SectionPos.sectionToBlockCoord(chunk.getPos().z(), this.localZ)
            );
        }
    }

    private static final class MutableBudget {
        private final boolean unlimited;
        private int remaining;

        private MutableBudget(int max) {
            this.unlimited = max <= 0;
            this.remaining = max;
        }

        private boolean hasBudget() {
            return this.unlimited || this.remaining > 0;
        }

        private boolean canSpend(int amount) {
            return this.unlimited || this.remaining >= amount;
        }

        private void spendOne() {
            if (!this.unlimited) {
                this.remaining--;
            }
        }
    }

    private static final class PlacementResult {
        private int changed;
        private int chunksVisited;
        private int skippedOccupied;
        private int skippedUnsupported;
        private int skippedOutOfBounds;
        private int skippedSource;
        private int skippedAirSource;
        private int skippedStaleChunks;
        private boolean limited;

        private void addPlacementSkip(PlacementSkipReason reason) {
            switch (reason) {
                case OCCUPIED, NON_MATCHING -> this.skippedOccupied++;
                case UNSUPPORTED -> this.skippedUnsupported++;
                case OUT_OF_BOUNDS -> this.skippedOutOfBounds++;
                case SOURCE -> this.skippedSource++;
                case AIR_SOURCE -> this.skippedAirSource++;
                case NONE -> {
                }
            }
        }
    }

    private static final class BreakResult {
        private int changed;
        private int chunksVisited;
        private int skippedNonMatching;
        private int skippedOutOfBounds;
        private int skippedSource;
        private int skippedAir;
        private int skippedStaleChunks;
        private boolean limited;
    }

    private enum PlacementSkipReason {
        NONE,
        OCCUPIED,
        UNSUPPORTED,
        OUT_OF_BOUNDS,
        SOURCE,
        AIR_SOURCE,
        NON_MATCHING
    }
}
