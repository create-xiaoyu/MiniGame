package com.xiaoyu.minigame.gamefeature.sameblockbreak.destruction;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import com.xiaoyu.minigame.MiniGame;
import com.xiaoyu.minigame.gamefeature.sameblockbreak.world.ForbiddenBlocks;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class DestructionManager {
    public static final DestructionManager INSTANCE = new DestructionManager();

    private static final ThreadLocal<Boolean> INTERNAL_BREAK = ThreadLocal.withInitial(() -> false);

    private final ConcurrentHashMap<ResourceKey<Level>, CopyOnWriteArrayList<DestructionTask>> activeTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ResourceKey<Level>, Long> preparingTasks = new ConcurrentHashMap<>();
    private final AtomicLong nextPreparationId = new AtomicLong();

    private DestructionManager() {
    }

    public static boolean isInternalBreak() {
        return INTERNAL_BREAK.get();
    }

    public static void runInternalBreak(Runnable action) {
        boolean wasInternal = INTERNAL_BREAK.get();
        INTERNAL_BREAK.set(true);
        try {
            action.run();
        } finally {
            INTERNAL_BREAK.set(wasInternal);
        }
    }

    public boolean start(ServerLevel level, Entity actor, BlockState triggerState, BlockPos center) {
        if (isInternalBreak() || triggerState.isAir()) {
            return false;
        }

        DestructionSettings settings = DestructionSettings.fromConfig();
        if (!settings.enabled()) {
            return false;
        }

        ResourceKey<Level> dimension = level.dimension();
        MinecraftServer server = level.getServer();
        if (!settings.allowConcurrentTasks() && (hasActiveTask(dimension) || preparingTasks.containsKey(dimension))) {
            sendAdminMessage(server, Component.translatable("minigame.sameblockbreak.task.already_active"));
            return false;
        }

        long preparationId = nextPreparationId.incrementAndGet();
        if (!settings.allowConcurrentTasks()) {
            Long previous = preparingTasks.putIfAbsent(dimension, preparationId);
            if (previous != null) {
                sendAdminMessage(server, Component.translatable("minigame.sameblockbreak.task.already_preparing"));
                return false;
            }
        }

        TargetMatcher target = TargetMatcher.from(triggerState, settings.matchBlockType());
        rememberForbiddenBlock(server, triggerState, settings);
        BlockPos immutableCenter = center.immutable();
        UUID breakerId = actor == null ? null : actor.getUUID();
        Component actorName = actorDisplayName(actor);
        Component brokenBlockName = triggerState.getBlock().getName();

        broadcastTrigger(server, actorName, brokenBlockName);
        sendAdminMessage(server, Component.translatable("minigame.sameblockbreak.task.preparing", target.displayName()));
        CompletableFuture
                .supplyAsync(() -> ChunkQueueBuilder.build(immutableCenter, settings.radius()))
                .whenComplete((queue, throwable) -> server.execute(() -> attachPreparedTask(
                        server,
                        dimension,
                        breakerId,
                        target,
                        immutableCenter,
                        settings,
                        preparationId,
                        queue,
                        throwable
                )));

        return true;
    }

    public void tick(MinecraftServer server) {
        for (ResourceKey<Level> dimension : List.copyOf(activeTasks.keySet())) {
            ServerLevel level = server.getLevel(dimension);
            CopyOnWriteArrayList<DestructionTask> tasks = activeTasks.get(dimension);
            if (tasks == null) {
                continue;
            }

            if (level == null) {
                for (DestructionTask task : tasks) {
                    task.cancel();
                }
                tasks.clear();
                activeTasks.remove(dimension, tasks);
                continue;
            }

            for (DestructionTask task : tasks) {
                boolean finished = task.tick(level, server);
                if (finished) {
                    tasks.remove(task);
                    sendFinishedMessage(server, task);
                }
            }

            if (tasks.isEmpty()) {
                activeTasks.remove(dimension, tasks);
            }
        }
    }

    public int cancelDimension(ResourceKey<Level> dimension) {
        Long preparing = preparingTasks.remove(dimension);
        int cancelled = preparing == null ? 0 : 1;

        CopyOnWriteArrayList<DestructionTask> tasks = activeTasks.remove(dimension);
        if (tasks != null) {
            for (DestructionTask task : tasks) {
                task.cancel();
                cancelled++;
            }
            tasks.clear();
        }

        return cancelled;
    }

    public int cancelAll() {
        int cancelled = 0;
        for (ResourceKey<Level> dimension : List.copyOf(preparingTasks.keySet())) {
            if (preparingTasks.remove(dimension) != null) {
                cancelled++;
            }
        }

        for (CopyOnWriteArrayList<DestructionTask> tasks : activeTasks.values()) {
            for (DestructionTask task : tasks) {
                task.cancel();
                cancelled++;
            }
            tasks.clear();
        }
        activeTasks.clear();
        return cancelled;
    }

    public Component status(ResourceKey<Level> dimension) {
        CopyOnWriteArrayList<DestructionTask> tasks = activeTasks.get(dimension);
        boolean preparing = preparingTasks.containsKey(dimension);
        if ((tasks == null || tasks.isEmpty()) && !preparing) {
            return Component.translatable("minigame.sameblockbreak.status.no_active", dimension.identifier().toString());
        }

        MutableComponent builder = Component.translatable("minigame.sameblockbreak.status.prefix");
        if (preparing) {
            builder.append(Component.translatable("minigame.sameblockbreak.status.preparing"));
        }
        if (tasks != null && !tasks.isEmpty()) {
            if (preparing) {
                builder.append(Component.literal("; "));
            }
            for (int i = 0; i < tasks.size(); i++) {
                if (i > 0) {
                    builder.append(Component.literal(" | "));
                }
                builder.append(tasks.get(i).summaryComponent());
            }
        }
        return builder;
    }

    public boolean hasActiveTask(ResourceKey<Level> dimension) {
        CopyOnWriteArrayList<DestructionTask> tasks = activeTasks.get(dimension);
        return tasks != null && !tasks.isEmpty();
    }

    private void attachPreparedTask(
            MinecraftServer server,
            ResourceKey<Level> dimension,
            UUID breakerId,
            TargetMatcher target,
            BlockPos center,
            DestructionSettings settings,
            long preparationId,
            long[] queue,
            Throwable throwable
    ) {
        if (!settings.allowConcurrentTasks()) {
            Long activePreparation = preparingTasks.remove(dimension);
            if (!Objects.equals(activePreparation, preparationId)) {
                return;
            }
        }

        if (throwable != null) {
            MiniGame.LOGGER.error("Failed to prepare SameBlockBreak queue", throwable);
            sendAdminMessage(server, Component.translatable("minigame.sameblockbreak.task.prepare_failed"));
            return;
        }

        if (queue == null || queue.length == 0) {
            sendAdminMessage(server, Component.translatable("minigame.sameblockbreak.task.no_chunks"));
            return;
        }

        if (!settings.allowConcurrentTasks() && hasActiveTask(dimension)) {
            sendAdminMessage(server, Component.translatable("minigame.sameblockbreak.task.active_before_ready"));
            return;
        }

        DestructionTask task = new DestructionTask(dimension, breakerId, target, center, queue, settings);
        activeTasks.computeIfAbsent(dimension, key -> new CopyOnWriteArrayList<>()).add(task);
        sendAdminMessage(server, Component.translatable("minigame.sameblockbreak.task.started", target.displayName(), queue.length));
        MiniGame.LOGGER.info("Started SameBlockBreak task for {} in {} over {} chunks", target.description(), dimension.identifier(), queue.length);
    }

    private void sendFinishedMessage(MinecraftServer server, DestructionTask task) {
        String state = task.isCancelled() ? "cancelled" : "completed";
        String message = "SameBlockBreak: " + state + " " + task.targetDescription()
                + ", destroyed " + task.blocksDestroyed()
                + " blocks, scanned " + task.blocksScanned()
                + " positions, natural breaks " + task.naturalBreaks()
                + ", skipped chunks " + task.skippedChunks()
                + ", elapsed " + task.elapsedMillis() + " ms.";
        String translationKey = task.isCancelled()
                ? "minigame.sameblockbreak.task.cancelled"
                : "minigame.sameblockbreak.task.completed";
        sendAdminMessage(server, Component.translatable(
                translationKey,
                task.targetDisplayName(),
                task.blocksDestroyed(),
                task.blocksScanned(),
                task.naturalBreaks(),
                task.skippedChunks(),
                task.elapsedMillis()
        ));
        MiniGame.LOGGER.info(message);
    }

    private void broadcastTrigger(MinecraftServer server, Component actorName, Component brokenBlockName) {
        server.getPlayerList().broadcastSystemMessage(
                Component.translatable("minigame.sameblockbreak.broadcast.trigger", actorName, brokenBlockName).withStyle(ChatFormatting.RED),
                false
        );
    }

    private void rememberForbiddenBlock(MinecraftServer server, BlockState triggerState, DestructionSettings settings) {
        if (settings.matchBlockType() && settings.rememberBrokenBlocksForever()) {
            ForbiddenBlocks.get(server).add(triggerState);
        }
    }

    private Component actorDisplayName(Entity actor) {
        return actor == null
                ? Component.translatable("minigame.sameblockbreak.actor.unknown")
                : actor.getDisplayName();
    }

    private void sendAdminMessage(MinecraftServer server, Component message) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (Commands.LEVEL_GAMEMASTERS.check(player.permissions())) {
                player.sendSystemMessage(message);
            }
        }
    }
}
