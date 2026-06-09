package com.xiaoyu.minigame.gamefeature.chunkplaceblock.placement;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import com.xiaoyu.minigame.MiniGame;
import com.xiaoyu.minigame.gamefeature.chunkplaceblock.config.ChunkPlaceBlockConfig;
import com.xiaoyu.minigame.gamefeature.chunkplaceblock.world.ChunkBreakBlockData;
import com.xiaoyu.minigame.gamefeature.chunkplaceblock.world.ChunkBreakBlockData.ChunkBreakRule;
import com.xiaoyu.minigame.gamefeature.chunkplaceblock.world.ChunkPlaceBlockData;
import com.xiaoyu.minigame.gamefeature.chunkplaceblock.world.ChunkPlaceBlockData.ChunkPlaceRule;
import com.xiaoyu.minigame.gamefeature.sameblockbreak.config.SameBlockBreakConfig;
import com.xiaoyu.minigame.gamefeature.sameblockbreak.world.ForbiddenBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

public final class ChunkPlaceBlockManager {
    public static final ChunkPlaceBlockManager INSTANCE = new ChunkPlaceBlockManager();

    private static final ThreadLocal<Boolean> INTERNAL_UPDATE = ThreadLocal.withInitial(() -> false);

    private final ConcurrentLinkedQueue<PendingPlacement> pendingPlacements = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<SyncKey, PendingBlockEntitySync> pendingBlockEntitySyncs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<LoadedChunkKey, PendingLoadedChunk> pendingLoadedChunks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ResourceKey<Level>, CopyOnWriteArrayList<ChunkPlaceBlockTask>> activeTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ResourceKey<Level>, Long> preparingTasks = new ConcurrentHashMap<>();
    private final AtomicLong nextPreparationId = new AtomicLong();

    private ChunkPlaceBlockManager() {
    }

    public static boolean isInternalUpdate() {
        return INTERNAL_UPDATE.get();
    }

    public static void runInternalUpdate(Runnable action) {
        boolean wasInternal = INTERNAL_UPDATE.get();
        INTERNAL_UPDATE.set(true);
        ForbiddenBlocks.beginPlayerPlacement();
        try {
            action.run();
        } finally {
            ForbiddenBlocks.endPlayerPlacement();
            INTERNAL_UPDATE.set(wasInternal);
        }
    }

    public void schedulePlacement(ServerLevel level, List<BlockPos> sourcePositions) {
        if (isInternalUpdate() || sourcePositions.isEmpty()) {
            return;
        }

        ChunkPlaceBlockSettings settings = ChunkPlaceBlockSettings.fromConfig();
        if (!settings.enabled()) {
            return;
        }

        List<BlockPos> immutablePositions = sourcePositions.stream()
                .map(BlockPos::immutable)
                .distinct()
                .toList();
        pendingPlacements.add(new PendingPlacement(
                level.dimension(),
                immutablePositions,
                level.getGameTime() + settings.placementDelayTicks()
        ));
    }

    public void onBlockEntityChanged(BlockEntity blockEntity) {
        if (isInternalUpdate()) {
            return;
        }

        ChunkPlaceBlockSettings settings = ChunkPlaceBlockSettings.fromConfig();
        if (!settings.enabled() || !settings.syncBlockEntityData() || blockEntity.isRemoved()) {
            return;
        }

        if (!(blockEntity.getLevel() instanceof ServerLevel level)) {
            return;
        }

        BlockPos pos = blockEntity.getBlockPos().immutable();
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !state.hasBlockEntity()) {
            return;
        }

        if (!ChunkPlaceBlockData.get(level.getServer()).hasManagedBlockEntity(level, pos, state)) {
            return;
        }

        SyncKey key = new SyncKey(level.dimension(), pos);
        pendingBlockEntitySyncs.put(key, new PendingBlockEntitySync(
                level.dimension(),
                pos,
                level.getGameTime() + settings.blockEntitySyncDelayTicks()
        ));
    }

    public void scheduleLoadedChunk(ServerLevel level, LevelChunk chunk) {
        if (isInternalUpdate()) {
            return;
        }

        ChunkPlaceBlockSettings settings = ChunkPlaceBlockSettings.fromConfig();
        if (!settings.enabled() || !settings.applyToFutureChunkLoads()) {
            return;
        }

        ChunkPos chunkPos = chunk.getPos();
        LoadedChunkKey key = new LoadedChunkKey(level.dimension(), chunkPos.x(), chunkPos.z());
        pendingLoadedChunks.put(key, new PendingLoadedChunk(
                level.dimension(),
                chunkPos.x(),
                chunkPos.z(),
                level.getGameTime() + 1L,
                0
        ));
    }

    public void startBreak(ServerLevel level, BlockState state, BlockPos sourcePos) {
        if (isInternalUpdate() || state.isAir()) {
            return;
        }

        ChunkPlaceBlockSettings settings = ChunkPlaceBlockSettings.fromConfig();
        if (!samePositionBreaksEnabled(settings)) {
            return;
        }

        BlockPos immutableSourcePos = sourcePos.immutable();
        PlacedBlockTemplate template = new PlacedBlockTemplate(immutableSourcePos, state, null);
        rememberBreakTemplate(level, template);
        start(level, ChunkPlaceOperation.DESTROY_SAME_POSITION_BLOCKS, List.of(template), immutableSourcePos);
    }

    public void tick(MinecraftServer server) {
        enforceSameBlockBreakCompatibility();
        processPendingPlacements(server);
        processPendingBlockEntitySyncs(server);
        processPendingLoadedChunks(server);
        tickTasks(server);
    }

    public int cancelAll() {
        pendingPlacements.clear();
        pendingBlockEntitySyncs.clear();
        pendingLoadedChunks.clear();

        int cancelled = 0;
        for (ResourceKey<Level> dimension : List.copyOf(preparingTasks.keySet())) {
            if (preparingTasks.remove(dimension) != null) {
                cancelled++;
            }
        }

        for (CopyOnWriteArrayList<ChunkPlaceBlockTask> tasks : activeTasks.values()) {
            for (ChunkPlaceBlockTask task : tasks) {
                task.cancel();
                cancelled++;
            }
            tasks.clear();
        }
        activeTasks.clear();
        return cancelled;
    }

    private void processPendingPlacements(MinecraftServer server) {
        int size = pendingPlacements.size();
        for (int i = 0; i < size; i++) {
            PendingPlacement pending = pendingPlacements.poll();
            if (pending == null) {
                return;
            }

            ServerLevel level = server.getLevel(pending.dimension());
            if (level == null) {
                continue;
            }

            if (level.getGameTime() < pending.dueGameTime()) {
                pendingPlacements.add(pending);
                continue;
            }

            List<PlacedBlockTemplate> templates = captureTemplates(level, pending.sourcePositions(), false);
            if (!templates.isEmpty()) {
                rememberTemplates(level, templates);
                start(level, ChunkPlaceOperation.PLACE_IN_EMPTY_BLOCKS, templates, pending.sourcePositions().getFirst());
            }
        }
    }

    private void processPendingBlockEntitySyncs(MinecraftServer server) {
        for (var entry : List.copyOf(pendingBlockEntitySyncs.entrySet())) {
            PendingBlockEntitySync pending = entry.getValue();
            ServerLevel level = server.getLevel(pending.dimension());
            if (level == null) {
                pendingBlockEntitySyncs.remove(entry.getKey(), pending);
                continue;
            }

            if (level.getGameTime() < pending.dueGameTime()) {
                continue;
            }

            if (!pendingBlockEntitySyncs.remove(entry.getKey(), pending)) {
                continue;
            }

            List<PlacedBlockTemplate> templates = captureTemplates(level, List.of(pending.sourcePos()), true);
            if (!templates.isEmpty()) {
                updateRememberedBlockEntityData(level, pending.sourcePos(), templates.getFirst());
                start(level, ChunkPlaceOperation.SYNC_BLOCK_ENTITY_DATA, templates, pending.sourcePos());
            }
        }
    }

    private void processPendingLoadedChunks(MinecraftServer server) {
        for (var entry : List.copyOf(pendingLoadedChunks.entrySet())) {
            PendingLoadedChunk pending = entry.getValue();
            ServerLevel level = server.getLevel(pending.dimension());
            if (level == null) {
                pendingLoadedChunks.remove(entry.getKey(), pending);
                continue;
            }

            if (level.getGameTime() < pending.dueGameTime()) {
                continue;
            }

            if (!pendingLoadedChunks.remove(entry.getKey(), pending)) {
                continue;
            }

            LevelChunk chunk = level.getChunkSource().getChunkNow(pending.chunkX(), pending.chunkZ());
            if (chunk == null) {
                if (pending.attempts() < 20) {
                    pendingLoadedChunks.put(entry.getKey(), pending.retry(level.getGameTime() + 1L));
                }
                continue;
            }

            applyRememberedRules(level, chunk);
        }
    }

    private void tickTasks(MinecraftServer server) {
        for (ResourceKey<Level> dimension : List.copyOf(activeTasks.keySet())) {
            ServerLevel level = server.getLevel(dimension);
            CopyOnWriteArrayList<ChunkPlaceBlockTask> tasks = activeTasks.get(dimension);
            if (tasks == null) {
                continue;
            }

            if (level == null) {
                for (ChunkPlaceBlockTask task : tasks) {
                    task.cancel();
                }
                tasks.clear();
                activeTasks.remove(dimension, tasks);
                continue;
            }

            for (ChunkPlaceBlockTask task : tasks) {
                boolean finished = task.tick(level);
                if (finished) {
                    tasks.remove(task);
                    MiniGame.LOGGER.info("ChunkPlaceBlock {} {}", task.isCancelled() ? "cancelled" : "completed", task.summary());
                }
            }

            if (tasks.isEmpty()) {
                activeTasks.remove(dimension, tasks);
            }
        }
    }

    private void start(ServerLevel level, ChunkPlaceOperation operation, List<PlacedBlockTemplate> templates, BlockPos center) {
        if (isInternalUpdate() || templates.isEmpty()) {
            return;
        }

        ChunkPlaceBlockSettings settings = ChunkPlaceBlockSettings.fromConfig();
        if (!settings.enabled() || operation == ChunkPlaceOperation.SYNC_BLOCK_ENTITY_DATA && !settings.syncBlockEntityData()) {
            return;
        }
        if (operation == ChunkPlaceOperation.DESTROY_SAME_POSITION_BLOCKS && !samePositionBreaksEnabled(settings)) {
            return;
        }

        ResourceKey<Level> dimension = level.dimension();
        if (!settings.allowConcurrentTasks() && (hasActiveTask(dimension) || preparingTasks.containsKey(dimension))) {
            MiniGame.LOGGER.debug("Skipped ChunkPlaceBlock {} task in {} because another task is active", operation, dimension.identifier());
            return;
        }

        long preparationId = nextPreparationId.incrementAndGet();
        if (!settings.allowConcurrentTasks()) {
            Long previous = preparingTasks.putIfAbsent(dimension, preparationId);
            if (previous != null) {
                return;
            }
        }

        MinecraftServer server = level.getServer();
        BlockPos immutableCenter = center.immutable();
        List<PlacedBlockTemplate> immutableTemplates = List.copyOf(templates);
        CompletableFuture
                .supplyAsync(() -> ChunkPositionQueueBuilder.build(immutableCenter, settings.radius()))
                .whenComplete((queue, throwable) -> server.execute(() -> attachPreparedTask(
                        dimension,
                        operation,
                        immutableTemplates,
                        immutableCenter,
                        settings,
                        preparationId,
                        queue,
                        throwable
                )));
    }

    private void attachPreparedTask(
            ResourceKey<Level> dimension,
            ChunkPlaceOperation operation,
            List<PlacedBlockTemplate> templates,
            BlockPos center,
            ChunkPlaceBlockSettings settings,
            long preparationId,
            long[] queue,
            @Nullable Throwable throwable
    ) {
        if (!settings.allowConcurrentTasks()) {
            Long activePreparation = preparingTasks.remove(dimension);
            if (!Objects.equals(activePreparation, preparationId)) {
                return;
            }
        }

        if (throwable != null) {
            MiniGame.LOGGER.error("Failed to prepare ChunkPlaceBlock queue", throwable);
            return;
        }

        if (queue == null || queue.length == 0) {
            return;
        }

        if (!settings.allowConcurrentTasks() && hasActiveTask(dimension)) {
            return;
        }

        ChunkPlaceBlockTask task = new ChunkPlaceBlockTask(operation, templates, center, queue, settings);
        activeTasks.computeIfAbsent(dimension, key -> new CopyOnWriteArrayList<>()).add(task);
        MiniGame.LOGGER.info("Started ChunkPlaceBlock {} task in {} over {} chunks", operation, dimension.identifier(), queue.length);
    }

    private boolean hasActiveTask(ResourceKey<Level> dimension) {
        CopyOnWriteArrayList<ChunkPlaceBlockTask> tasks = activeTasks.get(dimension);
        return tasks != null && !tasks.isEmpty();
    }

    private void rememberTemplates(ServerLevel level, List<PlacedBlockTemplate> templates) {
        ChunkPlaceBlockSettings settings = ChunkPlaceBlockSettings.fromConfig();
        ChunkPlaceBlockData data = ChunkPlaceBlockData.get(level.getServer());
        ChunkBreakBlockData breakData = ChunkBreakBlockData.get(level.getServer());
        for (PlacedBlockTemplate template : templates) {
            breakData.removeMatchingBreak(level, template.sourcePos(), template.state());
            if (settings.applyToFutureChunkLoads()) {
                data.addOrReplace(ChunkPlaceRule.from(
                        level,
                        template.sourcePos(),
                        settings.radius(),
                        template.state(),
                        template.blockEntityTag()
                ));
            }
        }
    }

    private void rememberBreakTemplate(ServerLevel level, PlacedBlockTemplate template) {
        ChunkPlaceBlockSettings settings = ChunkPlaceBlockSettings.fromConfig();
        ChunkPlaceBlockData.get(level.getServer()).removeMatchingPlacement(level, template.sourcePos(), template.state());
        if (settings.applyToFutureChunkLoads()) {
            ChunkBreakBlockData.get(level.getServer()).addOrReplace(ChunkBreakRule.from(
                    level,
                    template.sourcePos(),
                    settings.radius(),
                    template.state()
            ));
        }
    }

    private void updateRememberedBlockEntityData(ServerLevel level, BlockPos sourcePos, PlacedBlockTemplate template) {
        if (!template.hasBlockEntityData()) {
            return;
        }

        ChunkPlaceBlockData.get(level.getServer()).updateManagedBlockEntity(
                level,
                sourcePos,
                template.state(),
                template.blockEntityTag()
        );
    }

    private void applyRememberedRules(ServerLevel level, LevelChunk chunk) {
        ChunkPlaceBlockSettings settings = ChunkPlaceBlockSettings.fromConfig();
        if (!settings.enabled() || !settings.applyToFutureChunkLoads()) {
            return;
        }

        ChunkPlaceBlockData data = ChunkPlaceBlockData.get(level.getServer());
        List<ChunkPlaceRule> rules = data.rulesForChunk(level, chunk.getPos());
        if (rules.isEmpty()) {
            applyRememberedBreakRules(level, chunk);
            return;
        }

        int placed = 0;
        int blockEntityCopies = 0;
        for (ChunkPlaceRule rule : rules) {
            if (rule.isSourceChunk(chunk.getPos())) {
                continue;
            }

            BlockPos targetPos = rule.targetPos(chunk.getPos());
            if (!level.isInWorldBounds(targetPos) || !chunk.getBlockState(targetPos).isAir()) {
                continue;
            }

            boolean[] changed = new boolean[2];
            runInternalUpdate(() -> {
                if (level.setBlock(targetPos, rule.state(), ChunkPlaceBlockTask.PLACE_FLAGS)) {
                    changed[0] = true;
                    if (rule.hasBlockEntityData()
                            && ChunkPlaceBlockTask.applyBlockEntityTag(level, targetPos, rule.state(), rule.blockEntityTagFor(targetPos))) {
                        changed[1] = true;
                    }
                }
            });

            if (changed[0]) {
                placed++;
            }
            if (changed[1]) {
                blockEntityCopies++;
            }
        }

        if (placed > 0) {
            MiniGame.LOGGER.debug(
                    "Applied {} remembered ChunkPlaceBlock placement(s) in chunk {},{} (blockEntityCopies={})",
                    placed,
                    chunk.getPos().x(),
                    chunk.getPos().z(),
                    blockEntityCopies
            );
        }

        applyRememberedBreakRules(level, chunk);
    }

    private void applyRememberedBreakRules(ServerLevel level, LevelChunk chunk) {
        ChunkPlaceBlockSettings settings = ChunkPlaceBlockSettings.fromConfig();
        if (!settings.enabled() || !settings.applyToFutureChunkLoads() || !samePositionBreaksEnabled(settings)) {
            return;
        }

        List<ChunkBreakRule> rules = ChunkBreakBlockData.get(level.getServer()).rulesForChunk(level, chunk.getPos());
        if (rules.isEmpty()) {
            return;
        }

        int destroyed = 0;
        for (ChunkBreakRule rule : rules) {
            BlockPos targetPos = rule.targetPos(chunk.getPos());
            if (!level.isInWorldBounds(targetPos) || chunk.getBlockState(targetPos).getBlock() != rule.state().getBlock()) {
                continue;
            }

            boolean[] changed = new boolean[1];
            runInternalUpdate(() -> {
                if (level.setBlock(targetPos, Blocks.AIR.defaultBlockState(), ChunkPlaceBlockTask.SILENT_DESTROY_FLAGS)) {
                    changed[0] = true;
                }
            });

            if (changed[0]) {
                destroyed++;
            }
        }

        if (destroyed > 0) {
            MiniGame.LOGGER.debug(
                    "Applied {} remembered ChunkPlaceBlock break(s) in chunk {},{}",
                    destroyed,
                    chunk.getPos().x(),
                    chunk.getPos().z()
            );
        }
    }

    private boolean samePositionBreaksEnabled(ChunkPlaceBlockSettings settings) {
        if (SameBlockBreakConfig.ENABLED.getAsBoolean()) {
            enforceSameBlockBreakCompatibility();
            return false;
        }

        return settings.enabled()
                && settings.destroySamePositionOnBreak();
    }

    private void enforceSameBlockBreakCompatibility() {
        if (SameBlockBreakConfig.ENABLED.getAsBoolean()
                && ChunkPlaceBlockConfig.DESTROY_SAME_POSITION_ON_BREAK.getAsBoolean()) {
            ChunkPlaceBlockConfig.DESTROY_SAME_POSITION_ON_BREAK.set(false);
            ChunkPlaceBlockConfig.SPEC.save();
            MiniGame.LOGGER.info("Disabled ChunkPlaceBlock same-position breaking because SameBlockBreak is enabled");
        }
    }

    private List<PlacedBlockTemplate> captureTemplates(ServerLevel level, List<BlockPos> sourcePositions, boolean requireBlockEntity) {
        ChunkPlaceBlockSettings settings = ChunkPlaceBlockSettings.fromConfig();
        ArrayList<PlacedBlockTemplate> templates = new ArrayList<>();
        for (BlockPos sourcePos : sourcePositions) {
            if (!level.isInWorldBounds(sourcePos)) {
                continue;
            }

            BlockState state = level.getBlockState(sourcePos);
            if (state.isAir()) {
                continue;
            }

            CompoundTag blockEntityTag = null;
            if (settings.syncBlockEntityData() && state.hasBlockEntity()) {
                BlockEntity blockEntity = level.getBlockEntity(sourcePos);
                blockEntityTag = saveBlockEntity(level, blockEntity);
            }

            if (requireBlockEntity && blockEntityTag == null) {
                continue;
            }

            templates.add(new PlacedBlockTemplate(sourcePos, state, blockEntityTag));
        }
        return templates;
    }

    private @Nullable CompoundTag saveBlockEntity(ServerLevel level, @Nullable BlockEntity blockEntity) {
        if (blockEntity == null || blockEntity.isRemoved()) {
            return null;
        }

        try {
            return blockEntity.saveWithFullMetadata(level.registryAccess());
        } catch (RuntimeException exception) {
            MiniGame.LOGGER.warn("Failed to save block entity data at {}", blockEntity.getBlockPos(), exception);
            return null;
        }
    }

    private record PendingPlacement(ResourceKey<Level> dimension, List<BlockPos> sourcePositions, long dueGameTime) {
    }

    private record PendingBlockEntitySync(ResourceKey<Level> dimension, BlockPos sourcePos, long dueGameTime) {
    }

    private record SyncKey(ResourceKey<Level> dimension, BlockPos sourcePos) {
    }

    private record PendingLoadedChunk(ResourceKey<Level> dimension, int chunkX, int chunkZ, long dueGameTime, int attempts) {
        private PendingLoadedChunk retry(long dueGameTime) {
            return new PendingLoadedChunk(dimension, chunkX, chunkZ, dueGameTime, attempts + 1);
        }
    }

    private record LoadedChunkKey(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
    }
}
