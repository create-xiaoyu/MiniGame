package com.xiaoyu.minigame.gamefeature.sameblockbreak;

import com.xiaoyu.minigame.gamefeature.chunkplaceblock.ChunkPlaceBlockManager;
import com.xiaoyu.minigame.gamefeature.common.chunk.ChunkTracker;
import com.xiaoyu.minigame.gamefeature.sameblockbreak.config.SameBlockBreakConfig;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SameBlockBreakManager {
    private static final String FLUID_TARGET_PREFIX = "fluid:";
    private static final Map<ServerLevel, LevelState> LEVEL_STATES = new IdentityHashMap<>();
    private static final ThreadLocal<Integer> ENTITY_PLACEMENT_BYPASS_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> LEVEL_SET_BLOCK_WRITE_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static volatile Set<String> persistedPreventedBlockIds = Set.of();
    private static volatile Set<Block> persistedPreventedBlocks = Set.of();
    private static volatile Set<Fluid> persistedPreventedFluids = Set.of();
    private static volatile Set<Block> activePreventedBlocks = Set.of();
    private static volatile Set<Fluid> activePreventedFluids = Set.of();
    private static volatile boolean globalRulesLoaded;
    private static volatile long persistedRulesVersion;

    private SameBlockBreakManager() {
    }

    public static void startFromBreak(ServerLevel level, BlockPos origin, BlockState state, @Nullable Entity breaker) {
        if (!SameBlockBreakConfig.ENABLED.get() || state.isAir()) {
            return;
        }

        CleanupTarget target = blockTarget(state);
        if (target == null) {
            return;
        }

        ActivationResult activationResult = startTargetFromTrigger(level, target, origin, breaker, StartMessageMode.BLOCK_BREAK_BROADCAST);
        if (activationResult != ActivationResult.MAX_ACTIVE_TARGETS) {
            target.forgetConflictingChunkPlaceRules(level);
        }
    }

    public static void startFromBucketPickup(ServerLevel level, BlockPos origin, BlockState pickedState, @Nullable Entity picker) {
        if (!SameBlockBreakConfig.ENABLED.get() || !SameBlockBreakConfig.TRIGGER_BUCKET_FLUID_PICKUPS.get()) {
            return;
        }

        CleanupTarget target = fluidTarget(pickedState.getFluidState());
        if (target == null) {
            return;
        }

        ActivationResult activationResult = startTargetFromTrigger(level, target, origin, picker, StartMessageMode.BUCKET_PICKUP_BROADCAST);
        if (activationResult != ActivationResult.MAX_ACTIVE_TARGETS) {
            target.forgetConflictingChunkPlaceRules(level);
        }
    }

    private static ActivationResult startTargetFromTrigger(
            ServerLevel level,
            CleanupTarget target,
            BlockPos origin,
            @Nullable Entity breaker,
            StartMessageMode startMessageMode
    ) {
        LevelState levelState = stateFor(level);
        ActivationResult activationResult = levelState.activateFromTrigger(level, target, origin.immutable(), breaker, startMessageMode);
        if (activationResult == ActivationResult.ALREADY_RUNNING) {
            return activationResult;
        }

        if (activationResult == ActivationResult.MAX_ACTIVE_TARGETS) {
            if (breaker instanceof ServerPlayer player) {
                player.sendSystemMessage(Component.translatable(
                        "message.minigame.sameblockbreak.max_active",
                        SameBlockBreakConfig.MAX_ACTIVE_TARGETS.getAsInt()
                ));
            }
            return activationResult;
        }
        updateActivePreventionSet();

        if (SameBlockBreakConfig.PERSIST_CLEANUP_RULES.get()) {
            addPersistedRule(level.getServer(), target);
        }
        return activationResult;
    }

    private static void sendMessageToTriggerAndAdmins(ServerLevel level, @Nullable UUID triggerPlayerId, Component message) {
        ServerPlayer triggerPlayer = triggerPlayerId == null ? null : level.getServer().getPlayerList().getPlayer(triggerPlayerId);
        if (triggerPlayer != null) {
            triggerPlayer.sendSystemMessage(message);
        }

        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (triggerPlayer != null && player.getUUID().equals(triggerPlayer.getUUID())) {
                continue;
            }
            if (player.permissions().hasPermission(Permissions.COMMANDS_ADMIN)) {
                player.sendSystemMessage(message);
            }
        }
    }

    public static StartResult startFromCommand(ServerLevel level, String blockIdText, BlockPos origin, @Nullable Entity sourceEntity) {
        CleanupTarget target = resolveBlockTarget(blockIdText);
        if (target == null) {
            return StartResult.INVALID_BLOCK;
        }

        LevelState levelState = stateFor(level);
        ActivationResult activationResult = levelState.activateFromTrigger(level, target, origin.immutable(), sourceEntity, StartMessageMode.DIRECT_PLAYER);
        if (activationResult == ActivationResult.ALREADY_RUNNING) {
            target.forgetConflictingChunkPlaceRules(level);
            return StartResult.ALREADY_RUNNING;
        }
        if (activationResult == ActivationResult.MAX_ACTIVE_TARGETS) {
            return StartResult.MAX_ACTIVE_TARGETS;
        }
        updateActivePreventionSet();
        target.forgetConflictingChunkPlaceRules(level);

        if (SameBlockBreakConfig.PERSIST_CLEANUP_RULES.get()) {
            addPersistedRule(level.getServer(), target);
        }

        return StartResult.STARTED;
    }

    public static boolean forget(ServerLevel level, String blockIdText) {
        Set<String> targetIds = targetIdsForInput(blockIdText);
        if (targetIds.isEmpty()) {
            return false;
        }

        MinecraftServer server = level.getServer();
        ensureGlobalRulesLoaded(server);
        boolean changed = false;
        for (String targetId : targetIds) {
            changed |= savedData(server).removeTarget(targetId);
            for (LevelState levelState : LEVEL_STATES.values()) {
                changed |= levelState.removeTarget(targetId);
            }
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
        if (persistedPreventedBlocks.contains(block) || activePreventedBlocks.contains(block)) {
            return true;
        }

        FluidState fluidState = newState.getFluidState();
        return !fluidState.isEmpty()
                && (persistedPreventedFluids.contains(fluidState.getType()) || activePreventedFluids.contains(fluidState.getType()));
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

    public static void beginLevelSetBlockWrite() {
        LEVEL_SET_BLOCK_WRITE_DEPTH.set(LEVEL_SET_BLOCK_WRITE_DEPTH.get() + 1);
    }

    public static void endLevelSetBlockWrite() {
        int depth = LEVEL_SET_BLOCK_WRITE_DEPTH.get() - 1;
        if (depth <= 0) {
            LEVEL_SET_BLOCK_WRITE_DEPTH.remove();
        } else {
            LEVEL_SET_BLOCK_WRITE_DEPTH.set(depth);
        }
    }

    public static boolean isLevelSetBlockWriteActive() {
        return LEVEL_SET_BLOCK_WRITE_DEPTH.get() > 0;
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
        activePreventedFluids = Set.of();
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

    private static void addPersistedRule(MinecraftServer server, CleanupTarget target) {
        ensureGlobalRulesLoaded(server);
        if (savedData(server).addTarget(target.storageId())) {
            HashSet<String> updated = new HashSet<>(persistedPreventedBlockIds);
            updated.add(target.storageId());
            replacePersistedPreventionSet(updated);
        }
    }

    private static void mergeLegacyLevelSavedData(MinecraftServer server, SameBlockBreakSavedData globalData) {
        for (ServerLevel level : server.getAllLevels()) {
            SameBlockBreakSavedData legacyData = level.getDataStorage().get(SameBlockBreakSavedData.TYPE);
            if (legacyData == null || legacyData == globalData) {
                continue;
            }

            for (String targetId : legacyData.targetBlockIds()) {
                CleanupTarget target = resolveTarget(targetId);
                if (target != null) {
                    globalData.addTarget(target.storageId());
                }
            }
        }
    }

    private static void replacePersistedPreventionSet(Set<String> targetIds) {
        HashSet<String> validTargetIds = new HashSet<>();
        HashSet<Block> validBlocks = new HashSet<>();
        HashSet<Fluid> validFluids = new HashSet<>();
        for (String targetId : targetIds) {
            CleanupTarget target = resolveTarget(targetId);
            if (target != null) {
                validTargetIds.add(target.storageId());
                target.collectPreventedBlocks(validBlocks);
                target.collectPreventedFluids(validFluids);
            }
        }

        persistedPreventedBlockIds = Set.copyOf(validTargetIds);
        persistedPreventedBlocks = Set.copyOf(validBlocks);
        persistedPreventedFluids = Set.copyOf(validFluids);
        persistedRulesVersion++;
    }

    private static void updateActivePreventionSet() {
        HashSet<Block> blocks = new HashSet<>();
        HashSet<Fluid> fluids = new HashSet<>();
        for (LevelState levelState : LEVEL_STATES.values()) {
            levelState.collectPreventedTargets(blocks, fluids);
        }
        activePreventedBlocks = Set.copyOf(blocks);
        activePreventedFluids = Set.copyOf(fluids);
    }

    private static @Nullable CleanupTarget blockTarget(BlockState state) {
        if (state.isAir()) {
            return null;
        }

        Block block = state.getBlock();
        Identifier blockId = BuiltInRegistries.BLOCK.getKey(block);
        if (blockId == null || isDenylisted(blockId)) {
            return null;
        }

        return CleanupTarget.block(blockId, block);
    }

    private static @Nullable CleanupTarget fluidTarget(FluidState fluidState) {
        if (fluidState.isEmpty()) {
            return null;
        }

        Fluid sourceFluid = sourceFluid(fluidState.getType());
        Identifier fluidId = BuiltInRegistries.FLUID.getKey(sourceFluid);
        if (fluidId == null || isDenylisted(fluidId)) {
            return null;
        }

        return CleanupTarget.fluid(fluidId, sourceFluid);
    }

    private static @Nullable CleanupTarget resolveBlockTarget(String blockIdText) {
        Identifier blockId = Identifier.tryParse(blockIdText);
        if (blockId == null || !BuiltInRegistries.BLOCK.containsKey(blockId)) {
            return null;
        }

        Block block = BuiltInRegistries.BLOCK.getValue(blockId);
        if (block == null || block == Blocks.AIR) {
            return null;
        }

        if (isDenylisted(blockId)) {
            return null;
        }

        return CleanupTarget.block(blockId, block);
    }

    private static @Nullable CleanupTarget resolveTarget(String targetIdText) {
        if (targetIdText.startsWith(FLUID_TARGET_PREFIX)) {
            Identifier fluidId = Identifier.tryParse(targetIdText.substring(FLUID_TARGET_PREFIX.length()));
            if (fluidId == null || !BuiltInRegistries.FLUID.containsKey(fluidId) || isDenylisted(fluidId)) {
                return null;
            }

            Fluid fluid = BuiltInRegistries.FLUID.getValue(fluidId);
            if (fluid == null || fluid.defaultFluidState().isEmpty()) {
                return null;
            }

            Fluid sourceFluid = sourceFluid(fluid);
            Identifier sourceFluidId = BuiltInRegistries.FLUID.getKey(sourceFluid);
            return sourceFluidId == null ? null : CleanupTarget.fluid(sourceFluidId, sourceFluid);
        }

        return resolveBlockTarget(targetIdText);
    }

    private static Set<String> targetIdsForInput(String targetIdText) {
        HashSet<String> targetIds = new HashSet<>();
        CleanupTarget target = resolveTarget(targetIdText);
        if (target != null) {
            targetIds.add(target.storageId());
        }

        Identifier id = Identifier.tryParse(targetIdText);
        if (id != null && BuiltInRegistries.FLUID.containsKey(id)) {
            Fluid fluid = BuiltInRegistries.FLUID.getValue(id);
            if (fluid != null && !fluid.defaultFluidState().isEmpty()) {
                Fluid sourceFluid = sourceFluid(fluid);
                Identifier sourceFluidId = BuiltInRegistries.FLUID.getKey(sourceFluid);
                if (sourceFluidId != null && !isDenylisted(sourceFluidId)) {
                    targetIds.add(FLUID_TARGET_PREFIX + sourceFluidId);
                }
            }
        }

        return targetIds;
    }

    private static Fluid sourceFluid(Fluid fluid) {
        return fluid instanceof FlowingFluid flowingFluid ? flowingFluid.getSource() : fluid;
    }

    private static void collectFluidVariants(Fluid fluid, Set<Fluid> fluids) {
        fluids.add(fluid);
        if (fluid instanceof FlowingFluid flowingFluid) {
            fluids.add(flowingFluid.getSource());
            fluids.add(flowingFluid.getFlowing());
        }
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

    private record CleanupTarget(TargetKind kind, Identifier id, @Nullable Block block, @Nullable Fluid fluid) {
        private static CleanupTarget block(Identifier id, Block block) {
            return new CleanupTarget(TargetKind.BLOCK, id, block, null);
        }

        private static CleanupTarget fluid(Identifier id, Fluid fluid) {
            return new CleanupTarget(TargetKind.FLUID, id, null, fluid);
        }

        private boolean matches(BlockState state) {
            if (this.kind == TargetKind.BLOCK) {
                return state.is(this.block);
            }

            FluidState fluidState = state.getFluidState();
            return !fluidState.isEmpty() && sourceFluid(fluidState.getType()).isSame(this.fluid);
        }

        private Component displayName() {
            if (this.kind == TargetKind.BLOCK) {
                return this.block.getName();
            }

            BlockState legacyBlock = this.fluid.defaultFluidState().createLegacyBlock();
            if (!legacyBlock.isAir()) {
                return legacyBlock.getBlock().getName();
            }

            return Component.literal(this.id.toString());
        }

        private String storageId() {
            return this.kind == TargetKind.FLUID ? FLUID_TARGET_PREFIX + this.id : this.id.toString();
        }

        private int forgetConflictingChunkPlaceRules(ServerLevel level) {
            if (this.kind == TargetKind.BLOCK) {
                return this.block == null ? 0 : ChunkPlaceBlockManager.forgetPersistentRulesForBlockCleanup(level, this.block);
            }

            return this.fluid == null ? 0 : ChunkPlaceBlockManager.forgetPersistentRulesForFluidCleanup(level, this.fluid);
        }

        private void collectPreventedBlocks(Set<Block> blocks) {
            if (this.kind == TargetKind.BLOCK) {
                blocks.add(this.block);
            }
        }

        private void collectPreventedFluids(Set<Fluid> fluids) {
            if (this.kind == TargetKind.FLUID) {
                collectFluidVariants(this.fluid, fluids);
            }
        }
    }

    private enum TargetKind {
        BLOCK,
        FLUID
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
        private final Map<String, ActiveTarget> targets = new HashMap<>();
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
            for (String targetId : persistedPreventedBlockIds) {
                CleanupTarget target = resolveTarget(targetId);
                if (target != null) {
                    changed |= this.activatePersisted(target);
                    target.forgetConflictingChunkPlaceRules(level);
                }
            }
            if (changed) {
                updateActivePreventionSet();
            }
        }

        private ActivationResult activateFromTrigger(
                ServerLevel level,
                CleanupTarget cleanupTarget,
                BlockPos origin,
                @Nullable Entity breaker,
                StartMessageMode startMessageMode
        ) {
            ActiveTarget target = this.targets.get(cleanupTarget.storageId());
            if (target != null && target.hasWork()) {
                return ActivationResult.ALREADY_RUNNING;
            }

            if (target == null) {
                if (this.targets.size() >= SameBlockBreakConfig.MAX_ACTIVE_TARGETS.getAsInt()) {
                    return ActivationResult.MAX_ACTIVE_TARGETS;
                }

                target = new ActiveTarget(cleanupTarget);
                this.targets.put(cleanupTarget.storageId(), target);
            }

            target.refresh(level, origin, breaker, ++this.nextActivationSequence, startMessageMode);
            return ActivationResult.STARTED;
        }

        private boolean activatePersisted(CleanupTarget cleanupTarget) {
            if (this.targets.containsKey(cleanupTarget.storageId()) || this.targets.size() >= SameBlockBreakConfig.MAX_ACTIVE_TARGETS.getAsInt()) {
                return false;
            }

            this.targets.put(cleanupTarget.storageId(), new ActiveTarget(cleanupTarget));
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
                    SameBlockBreakConfig.SUPPORT_CHECKS_PER_TICK.getAsInt(),
                    supportReservedBlockChanges()
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

        private boolean removeTarget(String targetId) {
            Iterator<ActiveTarget> iterator = this.targets.values().iterator();
            while (iterator.hasNext()) {
                ActiveTarget target = iterator.next();
                if (target.targetIdString().equals(targetId)) {
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

        private void collectPreventedTargets(Set<Block> blocks, Set<Fluid> fluids) {
            for (ActiveTarget target : this.targets.values()) {
                target.collectPreventedTargets(blocks, fluids);
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
        private final CleanupTarget target;
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

        private ActiveTarget(CleanupTarget target) {
            this.target = target;
        }

        private void refresh(
                ServerLevel level,
                BlockPos origin,
                @Nullable Entity breaker,
                long activationSequence,
                StartMessageMode startMessageMode
        ) {
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

            if (SameBlockBreakConfig.SEND_START_MESSAGE.get() && startMessageMode != StartMessageMode.DIRECT_PLAYER && breaker != null) {
                level.getServer().getPlayerList().broadcastSystemMessage(
                        Component.translatable(
                                startMessageMode.translationKey(),
                                breaker.getDisplayName(),
                                this.target.displayName()
                        ).withStyle(ChatFormatting.RED),
                        false
                );
            } else if (breaker instanceof ServerPlayer player && SameBlockBreakConfig.SEND_START_MESSAGE.get()) {
                player.sendSystemMessage(Component.translatable("message.minigame.sameblockbreak.started", this.target.displayName()));
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
                    if (needsRemoval) {
                        ChunkPlaceBlockManager.forgetPersistentRulesForBreak(level, pos, state);
                    }
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
            if (this.target.kind() == TargetKind.FLUID) {
                changed = this.removeTargetFluid(level, pos, state);
            } else if (this.shouldUseNormalDestroy(task)) {
                boolean dropResources = this.remainingDropBlocks > 0;
                changed = level.destroyBlock(pos, dropResources, this.breaker(level), SameBlockBreakConfig.NORMAL_DESTROY_UPDATE_LIMIT.getAsInt());
                if (changed && dropResources) {
                    this.remainingDropBlocks--;
                }
            } else {
                changed = fastRemove(level, pos, state);
            }

            if (changed) {
                ChunkPlaceBlockManager.forgetPersistentRulesForBreak(level, pos, state);
                this.enqueueSupportAround(pos, SameBlockBreakConfig.SUPPORT_CASCADE_DEPTH.getAsInt());
            }

            return changed ? TaskResult.CHANGED : TaskResult.CONTINUE;
        }

        private boolean removeTargetFluid(ServerLevel level, BlockPos pos, BlockState state) {
            BlockState replacement = this.fluidRemovalReplacement(level, pos, state);
            if (replacement == null || replacement == state) {
                return false;
            }

            return level.setBlock(
                    pos,
                    replacement,
                    SameBlockBreakConfig.FAST_REMOVAL_FLAGS.getAsInt(),
                    SameBlockBreakConfig.FAST_REMOVAL_UPDATE_LIMIT.getAsInt()
            );
        }

        private @Nullable BlockState fluidRemovalReplacement(ServerLevel level, BlockPos pos, BlockState state) {
            if (!this.matches(state)) {
                return null;
            }

            if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) {
                BlockState dryState = state.setValue(BlockStateProperties.WATERLOGGED, false);
                return dryState.canSurvive(level, pos) ? dryState : Blocks.AIR.defaultBlockState();
            }

            return Blocks.AIR.defaultBlockState();
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
            return this.target.matches(state);
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

            Component message = Component.translatable(
                    SameBlockBreakConfig.PERSIST_CLEANUP_RULES.get()
                            ? "message.minigame.sameblockbreak.loaded_pass_complete_persistent"
                            : "message.minigame.sameblockbreak.loaded_pass_complete",
                    this.target.displayName()
            );
            sendMessageToTriggerAndAdmins(level, this.notifyPlayerId, message);
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

        private String targetIdString() {
            return this.target.storageId();
        }

        private void collectPreventedTargets(Set<Block> blocks, Set<Fluid> fluids) {
            this.target.collectPreventedBlocks(blocks);
            this.target.collectPreventedFluids(fluids);
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

    private static int supportReservedBlockChanges() {
        if (!SameBlockBreakConfig.RESERVE_BLOCK_CHANGES_FOR_SUPPORT_CLEANUP.get()
                || !SameBlockBreakConfig.CLEANUP_UNSUPPORTED_BLOCKS.get()
                || SameBlockBreakConfig.SUPPORT_CHECKS_PER_TICK.getAsInt() <= 0) {
            return 0;
        }

        int maxBlockChanges = SameBlockBreakConfig.MAX_BLOCK_CHANGES_PER_TICK.getAsInt();
        if (maxBlockChanges <= 1) {
            return 0;
        }

        return Math.min(SameBlockBreakConfig.RESERVED_SUPPORT_BLOCK_CHANGES_PER_TICK.getAsInt(), maxBlockChanges - 1);
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

    private enum StartMessageMode {
        DIRECT_PLAYER("message.minigame.sameblockbreak.started"),
        BLOCK_BREAK_BROADCAST("message.minigame.sameblockbreak.entity_destroyed"),
        BUCKET_PICKUP_BROADCAST("message.minigame.sameblockbreak.entity_bucket_picked_up");

        private final String translationKey;

        StartMessageMode(String translationKey) {
            this.translationKey = translationKey;
        }

        private String translationKey() {
            return this.translationKey;
        }
    }

    private static final class TickBudget {
        private int remainingSections;
        private int remainingScannedBlocks;
        private int remainingBlockChanges;
        private int remainingSupportChecks;
        private final int reservedSupportBlockChanges;
        private int stepRemainingSections = Integer.MAX_VALUE;
        private int stepRemainingScannedBlocks = Integer.MAX_VALUE;
        private int stepRemainingBlockChanges = Integer.MAX_VALUE;
        private int stepRemainingSupportChecks = Integer.MAX_VALUE;

        private TickBudget(int remainingSections, int remainingScannedBlocks, int remainingBlockChanges, int remainingSupportChecks, int reservedSupportBlockChanges) {
            this.remainingSections = remainingSections;
            this.remainingScannedBlocks = remainingScannedBlocks;
            this.remainingBlockChanges = remainingBlockChanges;
            this.remainingSupportChecks = remainingSupportChecks;
            this.reservedSupportBlockChanges = reservedSupportBlockChanges;
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
                    && this.remainingBlockChanges > this.reservedSupportBlockChanges
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
            return this.hasBlockScanBudget() || this.hasSupportBudget() && this.hasChangeBudget();
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
