package com.xiaoyu.minigame.gamefeature.sameblockbreak;

import com.xiaoyu.minigame.gamefeature.common.chunk.ChunkTracker;
import com.xiaoyu.minigame.gamefeature.sameblockbreak.config.SameBlockBreakConfig;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SameBlockBreakManager {
    private static final Map<ServerLevel, LevelState> LEVEL_STATES = new IdentityHashMap<>();
    private static final ThreadLocal<Integer> ENTITY_PLACEMENT_BYPASS_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static volatile Set<String> persistedPreventedBlockIds = Set.of();
    private static volatile Set<Block> persistedPreventedBlocks = Set.of();
    private static volatile Set<Block> activePreventedBlocks = Set.of();
    private static volatile boolean globalRulesLoaded;
    private static volatile long persistedRulesVersion;

    private SameBlockBreakManager() {
    }

    public static void startFromBreak(ServerLevel level, BlockPos origin, BlockState state, @Nullable Entity breaker) {
        if (!SameBlockBreakConfig.ENABLED.get() || state.isAir()) {
            return;
        }

        Block block = state.getBlock();
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(block);
        if (blockId == null || isDenylisted(blockId)) {
            return;
        }

        LevelState levelState = stateFor(level);
        ActivationResult activationResult = levelState.activateFromTrigger(level, block, blockId, origin.immutable(), breaker);
        if (activationResult == ActivationResult.ALREADY_RUNNING) {
            return;
        }

        if (activationResult == ActivationResult.MAX_ACTIVE_TARGETS) {
            if (breaker instanceof ServerPlayer player) {
                player.sendSystemMessage(Component.translatable(
                        "message.minigame.sameblockbreak.max_active",
                        SameBlockBreakConfig.MAX_ACTIVE_TARGETS.getAsInt()
                ));
            }
            return;
        }
        updateActivePreventionSet();

        if (SameBlockBreakConfig.PERSIST_CLEANUP_RULES.get()) {
            addPersistedRule(level.getServer(), blockId);
        }
    }

    public static StartResult startFromCommand(ServerLevel level, String blockIdText, BlockPos origin, @Nullable Entity sourceEntity) {
        ResolvedBlock resolvedBlock = resolveBlock(blockIdText);
        if (resolvedBlock == null || isDenylisted(resolvedBlock.id())) {
            return StartResult.INVALID_BLOCK;
        }

        LevelState levelState = stateFor(level);
        ActivationResult activationResult = levelState.activateFromTrigger(level, resolvedBlock.block(), resolvedBlock.id(), origin.immutable(), sourceEntity);
        if (activationResult == ActivationResult.ALREADY_RUNNING) {
            return StartResult.ALREADY_RUNNING;
        }
        if (activationResult == ActivationResult.MAX_ACTIVE_TARGETS) {
            return StartResult.MAX_ACTIVE_TARGETS;
        }
        updateActivePreventionSet();

        if (SameBlockBreakConfig.PERSIST_CLEANUP_RULES.get()) {
            addPersistedRule(level.getServer(), resolvedBlock.id());
        }

        return StartResult.STARTED;
    }

    public static boolean forget(ServerLevel level, String blockIdText) {
        Identifier blockId = Identifier.tryParse(blockIdText);
        if (blockId == null) {
            return false;
        }

        MinecraftServer server = level.getServer();
        ensureGlobalRulesLoaded(server);
        boolean changed = savedData(server).removeTarget(blockId.toString());
        for (LevelState levelState : LEVEL_STATES.values()) {
            changed |= levelState.removeTarget(blockId.toString());
        }
        replacePersistedPreventionSet(savedData(server).targetBlockIds());
        updateActivePreventionSet();
        return changed;
    }

    public static int clearRules(ServerLevel level) {
        MinecraftServer server = level.getServer();
        ensureGlobalRulesLoaded(server);
        int removed = savedData(server).clearTargets();
        for (LevelState levelState : LEVEL_STATES.values()) {
            removed += levelState.clearTargets();
        }
        replacePersistedPreventionSet(Set.of());
        updateActivePreventionSet();
        return removed;
    }

    public static void tick(ServerLevel level) {
        if (!SameBlockBreakConfig.ENABLED.get()) {
            return;
        }

        LevelState levelState = stateFor(level);
        levelState.loadPersistedTargets(level);
        if (levelState.isEmpty()) {
            return;
        }

        levelState.enqueueLoadedChunks(level);
        levelState.process(level);
        levelState.finishIdleTargets(level);
    }

    public static boolean shouldPreventNonEntityPlacement(ServerLevel level, BlockState newState) {
        ensureGlobalRulesLoaded(level.getServer());
        return shouldPreventNonEntityPlacement(newState);
    }

    public static boolean shouldPreventNonEntityPlacement(BlockState newState) {
        if (!SameBlockBreakConfig.ENABLED.get()
                || !SameBlockBreakConfig.PREVENT_NON_ENTITY_PLACEMENT_WITH_MIXIN.get()
                || newState.isAir()
                || isEntityPlacementBypassActive()) {
            return false;
        }

        Block block = newState.getBlock();
        return persistedPreventedBlocks.contains(block) || activePreventedBlocks.contains(block);
    }

    public static void beginEntityPlacementBypass() {
        ENTITY_PLACEMENT_BYPASS_DEPTH.set(ENTITY_PLACEMENT_BYPASS_DEPTH.get() + 1);
    }

    public static void endEntityPlacementBypass() {
        int depth = ENTITY_PLACEMENT_BYPASS_DEPTH.get() - 1;
        if (depth <= 0) {
            ENTITY_PLACEMENT_BYPASS_DEPTH.remove();
        } else {
            ENTITY_PLACEMENT_BYPASS_DEPTH.set(depth);
        }
    }

    public static void onChunkUnload(ServerLevel level, long chunkKey) {
        LevelState levelState = LEVEL_STATES.get(level);
        if (levelState != null) {
            levelState.removeQueuedChunk(chunkKey);
        }
    }

    public static void onLevelUnload(ServerLevel level) {
        LEVEL_STATES.remove(level);
        updateActivePreventionSet();
    }

    public static SameBlockBreakStatus status(ServerLevel level) {
        ensureGlobalRulesLoaded(level.getServer());
        LevelState levelState = LEVEL_STATES.get(level);
        int activeTargets = levelState == null ? 0 : levelState.activeTargetCount();
        int queuedSections = levelState == null ? 0 : levelState.queuedSectionCount();
        int completedSections = levelState == null ? 0 : levelState.completedSectionCount();
        int supportChecks = levelState == null ? 0 : levelState.supportCheckCount();
        int persistedRules = savedData(level.getServer()).targetCount();
        return new SameBlockBreakStatus(activeTargets, queuedSections, completedSections, supportChecks, persistedRules);
    }

    public static void loadGlobalRules(MinecraftServer server) {
        if (!SameBlockBreakConfig.PERSIST_CLEANUP_RULES.get()) {
            replacePersistedPreventionSet(Set.of());
            globalRulesLoaded = false;
            return;
        }

        SameBlockBreakSavedData globalData = savedData(server);
        mergeLegacyLevelSavedData(server, globalData);
        replacePersistedPreventionSet(globalData.targetBlockIds());
        globalRulesLoaded = true;
    }

    public static void clearAll() {
        LEVEL_STATES.clear();
        replacePersistedPreventionSet(Set.of());
        activePreventedBlocks = Set.of();
        globalRulesLoaded = false;
    }

    private static LevelState stateFor(ServerLevel level) {
        return LEVEL_STATES.computeIfAbsent(level, ignored -> new LevelState());
    }

    private static SameBlockBreakSavedData savedData(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(SameBlockBreakSavedData.TYPE);
    }

    private static void ensureGlobalRulesLoaded(MinecraftServer server) {
        if (!SameBlockBreakConfig.PERSIST_CLEANUP_RULES.get()) {
            if (!persistedPreventedBlockIds.isEmpty()) {
                replacePersistedPreventionSet(Set.of());
            }
            globalRulesLoaded = false;
            return;
        }

        if (!globalRulesLoaded) {
            loadGlobalRules(server);
        }
    }

    private static void addPersistedRule(MinecraftServer server, Identifier blockId) {
        ensureGlobalRulesLoaded(server);
        if (savedData(server).addTarget(blockId)) {
            HashSet<String> updated = new HashSet<>(persistedPreventedBlockIds);
            updated.add(blockId.toString());
            replacePersistedPreventionSet(updated);
        }
    }

    private static void mergeLegacyLevelSavedData(MinecraftServer server, SameBlockBreakSavedData globalData) {
        for (ServerLevel level : server.getAllLevels()) {
            SameBlockBreakSavedData legacyData = level.getDataStorage().get(SameBlockBreakSavedData.TYPE);
            if (legacyData == null || legacyData == globalData) {
                continue;
            }

            for (String targetBlockId : legacyData.targetBlockIds()) {
                ResolvedBlock resolvedBlock = resolveBlock(targetBlockId);
                if (resolvedBlock != null && !isDenylisted(resolvedBlock.id())) {
                    globalData.addTarget(resolvedBlock.id());
                }
            }
        }
    }

    private static void replacePersistedPreventionSet(Set<String> blockIds) {
        HashSet<String> validBlockIds = new HashSet<>();
        HashSet<Block> validBlocks = new HashSet<>();
        for (String blockId : blockIds) {
            ResolvedBlock resolvedBlock = resolveBlock(blockId);
            if (resolvedBlock != null && !isDenylisted(resolvedBlock.id())) {
                validBlockIds.add(resolvedBlock.id().toString());
                validBlocks.add(resolvedBlock.block());
            }
        }

        persistedPreventedBlockIds = Set.copyOf(validBlockIds);
        persistedPreventedBlocks = Set.copyOf(validBlocks);
        persistedRulesVersion++;
    }

    private static void updateActivePreventionSet() {
        HashSet<Block> blocks = new HashSet<>();
        for (LevelState levelState : LEVEL_STATES.values()) {
            levelState.collectTargetBlocks(blocks);
        }
        activePreventedBlocks = Set.copyOf(blocks);
    }

    private static @Nullable ResolvedBlock resolveBlock(String blockIdText) {
        Identifier blockId = Identifier.tryParse(blockIdText);
        if (blockId == null || !BuiltInRegistries.BLOCK.containsKey(blockId)) {
            return null;
        }

        Block block = BuiltInRegistries.BLOCK.getValue(blockId);
        if (block == null || block == Blocks.AIR) {
            return null;
        }

        return new ResolvedBlock(blockId, block);
    }

    private static boolean isDenylisted(Identifier blockId) {
        String normalized = blockId.toString().toLowerCase(Locale.ROOT);
        for (String entry : SameBlockBreakConfig.BLOCK_DENYLIST.get()) {
            if (normalized.equals(entry.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEntityPlacementBypassActive() {
        return ENTITY_PLACEMENT_BYPASS_DEPTH.get() > 0;
    }

    private record ResolvedBlock(Identifier id, Block block) {
    }

    public record SameBlockBreakStatus(
            int activeTargets,
            int queuedSections,
            int completedSections,
            int supportChecks,
            int persistedRules
    ) {
    }

    public enum StartResult {
        STARTED,
        ALREADY_RUNNING,
        MAX_ACTIVE_TARGETS,
        INVALID_BLOCK
    }

    private static final class LevelState {
        private final Map<Block, ActiveTarget> targets = new IdentityHashMap<>();
        private long loadedPersistedRulesVersion = -1L;
        private long nextActivationSequence;

        private void loadPersistedTargets(ServerLevel level) {
            if (!SameBlockBreakConfig.PERSIST_CLEANUP_RULES.get()) {
                return;
            }

            ensureGlobalRulesLoaded(level.getServer());
            long currentVersion = persistedRulesVersion;
            if (this.loadedPersistedRulesVersion == currentVersion) {
                return;
            }

            boolean changed = false;
            this.loadedPersistedRulesVersion = currentVersion;
            for (String targetBlockId : persistedPreventedBlockIds) {
                ResolvedBlock resolvedBlock = resolveBlock(targetBlockId);
                if (resolvedBlock != null && !isDenylisted(resolvedBlock.id())) {
                    changed |= this.activatePersisted(resolvedBlock.block(), resolvedBlock.id());
                }
            }
            if (changed) {
                updateActivePreventionSet();
            }
        }

        private ActivationResult activateFromTrigger(ServerLevel level, Block block, Identifier blockId, BlockPos origin, @Nullable Entity breaker) {
            ActiveTarget target = this.targets.get(block);
            if (target != null && target.hasWork()) {
                return ActivationResult.ALREADY_RUNNING;
            }

            if (target == null) {
                if (this.targets.size() >= SameBlockBreakConfig.MAX_ACTIVE_TARGETS.getAsInt()) {
                    return ActivationResult.MAX_ACTIVE_TARGETS;
                }

                target = new ActiveTarget(block, blockId);
                this.targets.put(block, target);
            }

            target.refresh(level, origin, breaker, ++this.nextActivationSequence);
            return ActivationResult.STARTED;
        }

        private boolean activatePersisted(Block block, Identifier blockId) {
            if (this.targets.containsKey(block) || this.targets.size() >= SameBlockBreakConfig.MAX_ACTIVE_TARGETS.getAsInt()) {
                return false;
            }

            this.targets.put(block, new ActiveTarget(block, blockId));
            return true;
        }

        private void enqueueLoadedChunks(ServerLevel level) {
            for (LevelChunk chunk : ChunkTracker.getLoadedChunks(level)) {
                int maxSectionY = queueMaxSectionY(chunk);
                if (maxSectionY < chunk.getMinSectionY()) {
                    continue;
                }

                for (ActiveTarget target : this.targets.values()) {
                    target.enqueueChunk(chunk, maxSectionY);
                }
            }
        }

        private void process(ServerLevel level) {
            TickBudget budget = new TickBudget(
                    SameBlockBreakConfig.MAX_SECTIONS_PER_TICK.getAsInt(),
                    SameBlockBreakConfig.MAX_SCANNED_BLOCKS_PER_TICK.getAsInt(),
                    SameBlockBreakConfig.MAX_BLOCK_CHANGES_PER_TICK.getAsInt(),
                    SameBlockBreakConfig.SUPPORT_CHECKS_PER_TICK.getAsInt()
            );

            List<ActiveTarget> workingTargets = this.workingTargets();
            if (workingTargets.isEmpty()) {
                return;
            }

            int targetCount = workingTargets.size();
            int sectionSlice = ceilDivPositive(SameBlockBreakConfig.MAX_SECTIONS_PER_TICK.getAsInt(), targetCount);
            int scanSlice = Math.max(256, ceilDivPositive(SameBlockBreakConfig.MAX_SCANNED_BLOCKS_PER_TICK.getAsInt(), targetCount));
            int changeSlice = Math.max(1, ceilDivPositive(SameBlockBreakConfig.MAX_BLOCK_CHANGES_PER_TICK.getAsInt(), targetCount));
            int supportSlice = Math.max(1, ceilDivPositive(SameBlockBreakConfig.SUPPORT_CHECKS_PER_TICK.getAsInt(), targetCount));

            for (ActiveTarget target : workingTargets) {
                budget.beginStep(sectionSlice, scanSlice, changeSlice, supportSlice);
                try {
                    if (budget.hasBlockScanBudget()) {
                        target.processSections(level, budget);
                    }
                    if (budget.hasSupportBudget()) {
                        target.processSupportChecks(level, budget);
                    }
                } finally {
                    budget.endStep();
                }

                if (!budget.hasAnyBudget()) {
                    break;
                }
            }
        }

        private List<ActiveTarget> workingTargets() {
            List<ActiveTarget> workingTargets = new ArrayList<>();
            for (ActiveTarget target : this.targets.values()) {
                if (target.hasWork()) {
                    workingTargets.add(target);
                }
            }

            workingTargets.sort(Comparator.comparingLong(ActiveTarget::activationSequence).reversed());
            return workingTargets;
        }

        private static int ceilDivPositive(int value, int divisor) {
            if (value <= 0) {
                return 0;
            }
            return (value + divisor - 1) / divisor;
        }

        private void finishIdleTargets(ServerLevel level) {
            Iterator<ActiveTarget> iterator = this.targets.values().iterator();
            while (iterator.hasNext()) {
                ActiveTarget target = iterator.next();
                if (!target.isIdle()) {
                    continue;
                }

                target.sendCompletionIfNeeded(level);
                if (!SameBlockBreakConfig.PERSIST_CLEANUP_RULES.get()) {
                    iterator.remove();
                }
            }
            updateActivePreventionSet();
        }

        private void removeQueuedChunk(long chunkKey) {
            for (ActiveTarget target : this.targets.values()) {
                target.removeQueuedChunk(chunkKey);
            }
        }

        private boolean removeTarget(String blockId) {
            Iterator<ActiveTarget> iterator = this.targets.values().iterator();
            while (iterator.hasNext()) {
                ActiveTarget target = iterator.next();
                if (target.blockIdString().equals(blockId)) {
                    iterator.remove();
                    return true;
                }
            }
            return false;
        }

        private int clearTargets() {
            int removed = this.targets.size();
            this.targets.clear();
            return removed;
        }

        private boolean isEmpty() {
            return this.targets.isEmpty();
        }

        private void collectTargetBlocks(Set<Block> blocks) {
            for (ActiveTarget target : this.targets.values()) {
                blocks.add(target.block());
            }
        }

        private int activeTargetCount() {
            return this.targets.size();
        }

        private int queuedSectionCount() {
            int count = 0;
            for (ActiveTarget target : this.targets.values()) {
                count += target.queuedSectionCount();
            }
            return count;
        }

        private int completedSectionCount() {
            int count = 0;
            for (ActiveTarget target : this.targets.values()) {
                count += target.completedSectionCount();
            }
            return count;
        }

        private int supportCheckCount() {
            int count = 0;
            for (ActiveTarget target : this.targets.values()) {
                count += target.supportCheckCount();
            }
            return count;
        }
    }

    private static final class ActiveTarget {
        private final Block block;
        private final Identifier blockId;
        private final List<SectionTask> tasks = new ArrayList<>();
        private final LongSet queuedSections = new LongOpenHashSet();
        private final LongSet completedSections = new LongOpenHashSet();
        private final ArrayDeque<SupportCheck> supportChecks = new ArrayDeque<>();
        private final LongSet queuedSupportChecks = new LongOpenHashSet();
        private @Nullable Long originSection;
        private @Nullable Long originPos;
        private @Nullable UUID breakerPlayerId;
        private @Nullable UUID notifyPlayerId;
        private int remainingDropBlocks;
        private boolean completionPending;
        private long activationSequence;

        private ActiveTarget(Block block, Identifier blockId) {
            this.block = block;
            this.blockId = blockId;
        }

        private void refresh(ServerLevel level, BlockPos origin, @Nullable Entity breaker, long activationSequence) {
            this.tasks.clear();
            this.queuedSections.clear();
            this.completedSections.clear();
            this.supportChecks.clear();
            this.queuedSupportChecks.clear();
            this.originSection = SectionPos.asLong(origin);
            this.originPos = origin.asLong();
            this.remainingDropBlocks = SameBlockBreakConfig.NORMAL_DESTROY_DROP_LIMIT.getAsInt();
            this.completionPending = SameBlockBreakConfig.SEND_COMPLETION_MESSAGE.get();
            this.breakerPlayerId = breaker instanceof ServerPlayer player ? player.getUUID() : null;
            this.notifyPlayerId = this.breakerPlayerId;
            this.activationSequence = activationSequence;

            if (breaker instanceof ServerPlayer player && SameBlockBreakConfig.SEND_START_MESSAGE.get()) {
                player.sendSystemMessage(Component.translatable("message.minigame.sameblockbreak.started", this.block.getName()));
            }

            for (LevelChunk chunk : ChunkTracker.getLoadedChunks(level)) {
                this.enqueueChunk(chunk);
            }
        }

        private void enqueueChunk(LevelChunk chunk) {
            int maxSectionY = queueMaxSectionY(chunk);
            if (maxSectionY < chunk.getMinSectionY()) {
                return;
            }

            this.enqueueChunk(chunk, maxSectionY);
        }

        private void enqueueChunk(LevelChunk chunk, int maxSectionY) {
            ChunkPos chunkPos = chunk.getPos();
            for (int sectionY = chunk.getMinSectionY(); sectionY <= maxSectionY; sectionY++) {
                long sectionKey = SectionPos.asLong(chunkPos.x(), sectionY, chunkPos.z());
                if (this.completedSections.contains(sectionKey) || this.queuedSections.contains(sectionKey)) {
                    continue;
                }

                LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
                if (section.hasOnlyAir() || !section.maybeHas(this::matches)) {
                    continue;
                }

                this.queuedSections.add(sectionKey);
                this.tasks.add(new SectionTask(chunkPos.x(), sectionY, chunkPos.z(), sectionKey));
            }
        }

        private void processSections(ServerLevel level, TickBudget budget) {
            while (!this.tasks.isEmpty() && budget.hasBlockScanBudget()) {
                int taskIndex = this.findNearestTaskIndex(level);
                SectionTask task = this.tasks.get(taskIndex);
                TaskResult result = task.process(level, this, budget);

                if (result == TaskResult.UNLOADED) {
                    this.queuedSections.remove(task.sectionKey());
                    this.tasks.remove(taskIndex);
                    continue;
                }

                if (result == TaskResult.COMPLETE) {
                    this.queuedSections.remove(task.sectionKey());
                    this.completedSections.add(task.sectionKey());
                    this.tasks.remove(taskIndex);
                    budget.completedSection();
                    if (!budget.hasSectionBudget()) {
                        return;
                    }
                    continue;
                }

                return;
            }
        }

        private void processSupportChecks(ServerLevel level, TickBudget budget) {
            if (!SameBlockBreakConfig.CLEANUP_UNSUPPORTED_BLOCKS.get()) {
                this.supportChecks.clear();
                this.queuedSupportChecks.clear();
                return;
            }

            int checksThisTick = this.supportChecks.size();
            while (checksThisTick-- > 0 && !this.supportChecks.isEmpty() && budget.hasSupportBudget() && budget.hasChangeBudget()) {
                SupportCheck check = this.supportChecks.removeFirst();
                budget.supportChecked();

                BlockPos pos = BlockPos.of(check.pos());
                if (!level.isInWorldBounds(pos)) {
                    this.queuedSupportChecks.remove(check.pos());
                    continue;
                }

                if (!isChunkLoaded(level, pos)) {
                    this.supportChecks.addLast(check);
                    continue;
                }

                BlockState state = level.getBlockState(pos);
                if (state.isAir() || this.matches(state)) {
                    this.queuedSupportChecks.remove(check.pos());
                    continue;
                }

                boolean hasLoadedNeighbours = hasLoadedNeighbours(level, pos);
                boolean needsRemoval = !state.canSurvive(level, pos);
                BlockState updatedState = state;
                if (!needsRemoval && hasLoadedNeighbours) {
                    updatedState = Block.updateFromNeighbourShapes(state, level, pos);
                    needsRemoval = updatedState.isAir();
                }

                boolean changed = false;
                if (needsRemoval) {
                    changed = fastRemove(level, pos, state);
                } else if (updatedState != state) {
                    changed = level.setBlock(
                            pos,
                            updatedState,
                            SameBlockBreakConfig.FAST_REMOVAL_FLAGS.getAsInt(),
                            SameBlockBreakConfig.FAST_REMOVAL_UPDATE_LIMIT.getAsInt()
                    );
                }

                if (changed) {
                    budget.changedBlock();
                    this.enqueueSupportAround(pos, check.depth() - 1);
                }

                this.queuedSupportChecks.remove(check.pos());
            }
        }

        private TaskResult removeTargetBlock(ServerLevel level, BlockPos pos, BlockState state, SectionTask task) {
            if (this.originPos != null && pos.asLong() == this.originPos) {
                return TaskResult.CONTINUE;
            }

            boolean changed;
            if (this.shouldUseNormalDestroy(task)) {
                boolean dropResources = this.remainingDropBlocks > 0;
                changed = level.destroyBlock(pos, dropResources, this.breaker(level), SameBlockBreakConfig.NORMAL_DESTROY_UPDATE_LIMIT.getAsInt());
                if (changed && dropResources) {
                    this.remainingDropBlocks--;
                }
            } else {
                changed = fastRemove(level, pos, state);
            }

            if (changed) {
                this.enqueueSupportAround(pos, SameBlockBreakConfig.SUPPORT_CASCADE_DEPTH.getAsInt());
            }

            return changed ? TaskResult.CHANGED : TaskResult.CONTINUE;
        }

        private boolean shouldUseNormalDestroy(SectionTask task) {
            if (this.originSection == null) {
                return false;
            }

            int radius = SameBlockBreakConfig.NORMAL_DESTROY_SECTION_RADIUS.getAsInt();
            return Math.abs(task.sectionX() - SectionPos.x(this.originSection)) <= radius
                    && Math.abs(task.sectionY() - SectionPos.y(this.originSection)) <= radius
                    && Math.abs(task.sectionZ() - SectionPos.z(this.originSection)) <= radius;
        }

        private boolean fastRemove(ServerLevel level, BlockPos pos, BlockState state) {
            BlockState replacement = state.getFluidState().createLegacyBlock();
            return level.setBlock(
                    pos,
                    replacement,
                    SameBlockBreakConfig.FAST_REMOVAL_FLAGS.getAsInt(),
                    SameBlockBreakConfig.FAST_REMOVAL_UPDATE_LIMIT.getAsInt()
            );
        }

        private void enqueueSupportAround(BlockPos pos, int depth) {
            if (!SameBlockBreakConfig.CLEANUP_UNSUPPORTED_BLOCKS.get() || depth < 0) {
                return;
            }

            for (Direction direction : Direction.values()) {
                BlockPos supportPos = pos.relative(direction);
                long supportKey = supportPos.asLong();
                if (this.queuedSupportChecks.add(supportKey)) {
                    this.supportChecks.addLast(new SupportCheck(supportKey, depth));
                }
            }
        }

        private int findNearestTaskIndex(ServerLevel level) {
            int bestIndex = 0;
            double bestDistance = Double.MAX_VALUE;
            for (int i = 0; i < this.tasks.size(); i++) {
                double distance = this.tasks.get(i).distanceToNearestPlayerSqr(level, this.originSection);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = i;
                }
            }
            return bestIndex;
        }

        private @Nullable Entity breaker(ServerLevel level) {
            if (this.breakerPlayerId == null) {
                return null;
            }
            return level.getServer().getPlayerList().getPlayer(this.breakerPlayerId);
        }

        private boolean matches(BlockState state) {
            return state.is(this.block);
        }

        private void removeQueuedChunk(long chunkKey) {
            for (int i = this.tasks.size() - 1; i >= 0; i--) {
                SectionTask task = this.tasks.get(i);
                if (task.chunkKey() == chunkKey) {
                    this.queuedSections.remove(task.sectionKey());
                    this.tasks.remove(i);
                }
            }
        }

        private void sendCompletionIfNeeded(ServerLevel level) {
            if (!this.completionPending || this.notifyPlayerId == null) {
                return;
            }

            ServerPlayer player = level.getServer().getPlayerList().getPlayer(this.notifyPlayerId);
            if (player != null) {
                if (SameBlockBreakConfig.PERSIST_CLEANUP_RULES.get()) {
                    player.sendSystemMessage(Component.translatable("message.minigame.sameblockbreak.loaded_pass_complete_persistent", this.block.getName()));
                } else {
                    player.sendSystemMessage(Component.translatable("message.minigame.sameblockbreak.loaded_pass_complete", this.block.getName()));
                }
            }
            this.completionPending = false;
        }

        private boolean isIdle() {
            return this.tasks.isEmpty() && this.supportChecks.isEmpty();
        }

        private boolean hasWork() {
            return !this.tasks.isEmpty() || !this.supportChecks.isEmpty();
        }

        private long activationSequence() {
            return this.activationSequence;
        }

        private String blockIdString() {
            return this.blockId.toString();
        }

        private Block block() {
            return this.block;
        }

        private int queuedSectionCount() {
            return this.tasks.size();
        }

        private int completedSectionCount() {
            return this.completedSections.size();
        }

        private int supportCheckCount() {
            return this.supportChecks.size();
        }
    }

    private static final class SectionTask {
        private final int sectionX;
        private final int sectionY;
        private final int sectionZ;
        private final long sectionKey;
        private final long chunkKey;
        private int nextIndex;

        private SectionTask(int sectionX, int sectionY, int sectionZ, long sectionKey) {
            this.sectionX = sectionX;
            this.sectionY = sectionY;
            this.sectionZ = sectionZ;
            this.sectionKey = sectionKey;
            this.chunkKey = ChunkPos.pack(sectionX, sectionZ);
        }

        private TaskResult process(ServerLevel level, ActiveTarget target, TickBudget budget) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(this.sectionX, this.sectionZ);
            if (chunk == null) {
                return TaskResult.UNLOADED;
            }

            LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(this.sectionY));
            if (this.nextIndex == 0 && (section.hasOnlyAir() || !section.maybeHas(target::matches))) {
                return TaskResult.COMPLETE;
            }

            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            boolean changedAny = false;
            while (this.nextIndex < LevelChunkSection.SECTION_SIZE && budget.hasBlockScanBudget()) {
                int localIndex = this.nextIndex++;
                int localX = localIndex & 15;
                int localZ = localIndex >> 4 & 15;
                int localY = localIndex >> 8 & 15;
                pos.set(
                        SectionPos.sectionToBlockCoord(this.sectionX, localX),
                        SectionPos.sectionToBlockCoord(this.sectionY, localY),
                        SectionPos.sectionToBlockCoord(this.sectionZ, localZ)
                );
                budget.scannedBlock();

                BlockState state = section.getBlockState(localX, localY, localZ);
                if (!target.matches(state)) {
                    continue;
                }

                if (!budget.hasChangeBudget()) {
                    this.nextIndex--;
                    return changedAny ? TaskResult.PARTIAL_CHANGED : TaskResult.PARTIAL;
                }

                TaskResult result = target.removeTargetBlock(level, pos.immutable(), state, this);
                if (result == TaskResult.CHANGED) {
                    changedAny = true;
                    budget.changedBlock();
                }
            }

            return this.nextIndex >= LevelChunkSection.SECTION_SIZE ? TaskResult.COMPLETE : changedAny ? TaskResult.PARTIAL_CHANGED : TaskResult.PARTIAL;
        }

        private double distanceToNearestPlayerSqr(ServerLevel level, @Nullable Long originSection) {
            double centerX = SectionPos.sectionToBlockCoord(this.sectionX) + 8.0D;
            double centerY = SectionPos.sectionToBlockCoord(this.sectionY) + 8.0D;
            double centerZ = SectionPos.sectionToBlockCoord(this.sectionZ) + 8.0D;

            double bestDistance = Double.MAX_VALUE;
            for (ServerPlayer player : level.players()) {
                double distance = player.distanceToSqr(centerX, centerY, centerZ);
                if (distance < bestDistance) {
                    bestDistance = distance;
                }
            }

            if (bestDistance != Double.MAX_VALUE || originSection == null) {
                return bestDistance;
            }

            double originX = SectionPos.sectionToBlockCoord(SectionPos.x(originSection)) + 8.0D;
            double originY = SectionPos.sectionToBlockCoord(SectionPos.y(originSection)) + 8.0D;
            double originZ = SectionPos.sectionToBlockCoord(SectionPos.z(originSection)) + 8.0D;
            double dx = centerX - originX;
            double dy = centerY - originY;
            double dz = centerZ - originZ;
            return dx * dx + dy * dy + dz * dz;
        }

        private int sectionX() {
            return this.sectionX;
        }

        private int sectionY() {
            return this.sectionY;
        }

        private int sectionZ() {
            return this.sectionZ;
        }

        private long sectionKey() {
            return this.sectionKey;
        }

        private long chunkKey() {
            return this.chunkKey;
        }
    }

    private static boolean hasLoadedNeighbours(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (!isChunkLoaded(level, pos.relative(direction))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isChunkLoaded(ServerLevel level, BlockPos pos) {
        return level.isInWorldBounds(pos)
                && level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())) != null;
    }

    private static int queueMaxSectionY(LevelChunk chunk) {
        if (!SameBlockBreakConfig.SKIP_SECTIONS_ABOVE_HIGHEST_FILLED.get()) {
            return chunk.getMaxSectionY();
        }

        int highestFilledSectionIndex = chunk.getHighestFilledSectionIndex();
        if (highestFilledSectionIndex < 0) {
            return chunk.getMinSectionY() - 1;
        }

        return Math.min(chunk.getMaxSectionY(), chunk.getSectionYFromSectionIndex(highestFilledSectionIndex));
    }

    private record SupportCheck(long pos, int depth) {
    }

    private enum TaskResult {
        CONTINUE,
        CHANGED,
        PARTIAL,
        PARTIAL_CHANGED,
        COMPLETE,
        UNLOADED
    }

    private enum ActivationResult {
        STARTED,
        ALREADY_RUNNING,
        MAX_ACTIVE_TARGETS
    }

    private static final class TickBudget {
        private int remainingSections;
        private int remainingScannedBlocks;
        private int remainingBlockChanges;
        private int remainingSupportChecks;
        private int stepRemainingSections = Integer.MAX_VALUE;
        private int stepRemainingScannedBlocks = Integer.MAX_VALUE;
        private int stepRemainingBlockChanges = Integer.MAX_VALUE;
        private int stepRemainingSupportChecks = Integer.MAX_VALUE;

        private TickBudget(int remainingSections, int remainingScannedBlocks, int remainingBlockChanges, int remainingSupportChecks) {
            this.remainingSections = remainingSections;
            this.remainingScannedBlocks = remainingScannedBlocks;
            this.remainingBlockChanges = remainingBlockChanges;
            this.remainingSupportChecks = remainingSupportChecks;
        }

        private void beginStep(int sections, int scannedBlocks, int blockChanges, int supportChecks) {
            this.stepRemainingSections = sections;
            this.stepRemainingScannedBlocks = scannedBlocks;
            this.stepRemainingBlockChanges = blockChanges;
            this.stepRemainingSupportChecks = supportChecks;
        }

        private void endStep() {
            this.stepRemainingSections = Integer.MAX_VALUE;
            this.stepRemainingScannedBlocks = Integer.MAX_VALUE;
            this.stepRemainingBlockChanges = Integer.MAX_VALUE;
            this.stepRemainingSupportChecks = Integer.MAX_VALUE;
        }

        private boolean hasBlockScanBudget() {
            return this.remainingSections > 0
                    && this.stepRemainingSections > 0
                    && this.remainingScannedBlocks > 0
                    && this.stepRemainingScannedBlocks > 0
                    && this.remainingBlockChanges > 0
                    && this.stepRemainingBlockChanges > 0;
        }

        private boolean hasSectionBudget() {
            return this.remainingSections > 0 && this.stepRemainingSections > 0;
        }

        private boolean hasChangeBudget() {
            return this.remainingBlockChanges > 0 && this.stepRemainingBlockChanges > 0;
        }

        private boolean hasSupportBudget() {
            return this.remainingSupportChecks > 0 && this.stepRemainingSupportChecks > 0;
        }

        private boolean hasAnyBudget() {
            return this.hasBlockScanBudget() || this.hasSupportBudget();
        }

        private void completedSection() {
            this.remainingSections--;
            this.stepRemainingSections--;
        }

        private void scannedBlock() {
            this.remainingScannedBlocks--;
            this.stepRemainingScannedBlocks--;
        }

        private void changedBlock() {
            this.remainingBlockChanges--;
            this.stepRemainingBlockChanges--;
        }

        private void supportChecked() {
            this.remainingSupportChecks--;
            this.stepRemainingSupportChecks--;
        }
    }
}
