package com.xiaoyu.minigame.gamefeature.common.chunk;

import com.xiaoyu.minigame.gamefeature.common.config.CommonConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class ChunkTracker {
    private static final Map<ServerLevel, Long2ObjectMap<LevelChunk>> LOADED = new IdentityHashMap<>();
    private static final Map<ServerLevel, Long2ObjectMap<PendingChunk>> PENDING = new IdentityHashMap<>();

    public static Collection<LevelChunk> getLoadedChunks(ServerLevel level) {
        Long2ObjectMap<LevelChunk> chunks = LOADED.get(level);
        return chunks == null ? List.of() : List.copyOf(chunks.values());
    }

    public static void clearAll() {
        LOADED.clear();
        PENDING.clear();
    }

    @SubscribeEvent
    public static void onChunkEventLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        LevelChunk chunk = event.getChunk();
        long chunkKey = chunk.getPos().pack();

        PENDING.computeIfAbsent(level, ignored -> new Long2ObjectOpenHashMap<>())
                .put(chunkKey, new PendingChunk(chunk, 0));
    }

    @SubscribeEvent
    public static void onLevelTickEventPost(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        Long2ObjectMap<PendingChunk> pending = PENDING.get(level);
        if (pending == null || pending.isEmpty()) {
            return;
        }

        Long2ObjectMap<LevelChunk> loaded = LOADED.computeIfAbsent(level, ignored -> new Long2ObjectOpenHashMap<>());
        Long2ObjectMap<PendingChunk> stillPending = new Long2ObjectOpenHashMap<>();

        for (Long2ObjectMap.Entry<PendingChunk> entry : pending.long2ObjectEntrySet()) {
            PendingChunk pendingChunk = entry.getValue();
            LevelChunk chunk = pendingChunk.chunk();
            ChunkPos pos = chunk.getPos();

            LevelChunk currentChunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());

            int nextPendingTicks = pendingChunk.pendingTicks() + 1;

            if (currentChunk == chunk) {
                loaded.put(entry.getLongKey(), chunk);
            } else if (nextPendingTicks <= CommonConfig.MAX_PENDING_TICKS.getAsInt()) {
                stillPending.put(entry.getLongKey(), pendingChunk.nextTick());
            }
        }

        if (stillPending.isEmpty()) {
            PENDING.remove(level);
        } else {
            PENDING.put(level, stillPending);
        }
    }

    @SubscribeEvent
    public static void onChunkEventUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        long chunkKey = event.getChunk().getPos().pack();

        removeLoaded(level, chunkKey);
        removePending(level, chunkKey);
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        LOADED.remove(level);
        PENDING.remove(level);
    }

    private static void removeLoaded(ServerLevel level, long chunkKey) {
        Long2ObjectMap<LevelChunk> chunks = LOADED.get(level);
        if (chunks == null) {
            return;
        }

        chunks.remove(chunkKey);

        if (chunks.isEmpty()) {
            LOADED.remove(level);
        }
    }

    private static void removePending(ServerLevel level, long chunkKey) {
        Long2ObjectMap<PendingChunk> chunks = PENDING.get(level);
        if (chunks == null) {
            return;
        }

        chunks.remove(chunkKey);

        if (chunks.isEmpty()) {
            PENDING.remove(level);
        }
    }

    private record PendingChunk(LevelChunk chunk, int pendingTicks) {
        private PendingChunk nextTick() {
            return new PendingChunk(this.chunk, this.pendingTicks + 1);
        }
    }
}