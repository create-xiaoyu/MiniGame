package com.xiaoyu.minigame.gamefeature.chunkplaceblock.world;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.xiaoyu.minigame.MiniGame;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public final class ChunkBreakBlockData extends SavedData {
    private static final Identifier DATA_ID = Identifier.fromNamespaceAndPath(MiniGame.MODID, "chunk_break_block_rules");

    public static final Codec<ChunkBreakBlockData> CODEC = ChunkBreakRule.CODEC.listOf()
            .xmap(ChunkBreakBlockData::new, ChunkBreakBlockData::rulesForCodec);
    public static final SavedDataType<ChunkBreakBlockData> TYPE = new SavedDataType<>(DATA_ID, ChunkBreakBlockData::new, CODEC);

    private final ArrayList<ChunkBreakRule> rules = new ArrayList<>();

    public ChunkBreakBlockData() {
    }

    private ChunkBreakBlockData(List<ChunkBreakRule> rules) {
        this.rules.addAll(rules);
    }

    public static ChunkBreakBlockData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public void addOrReplace(ChunkBreakRule rule) {
        rules.removeIf(existing -> existing.sameBreakSlot(rule));
        rules.add(rule);
        setDirty();
    }

    public void removeMatchingBreak(ServerLevel level, BlockPos sourcePos, BlockState state) {
        if (rules.removeIf(rule -> rule.matchesSlotAndBlock(level.dimension(), sourcePos, state))) {
            setDirty();
        }
    }

    public List<ChunkBreakRule> rulesForChunk(ServerLevel level, ChunkPos chunkPos) {
        ArrayList<ChunkBreakRule> result = new ArrayList<>();
        for (ChunkBreakRule rule : rules) {
            if (rule.appliesTo(level.dimension(), chunkPos)) {
                result.add(rule);
            }
        }
        return result;
    }

    private List<ChunkBreakRule> rulesForCodec() {
        return List.copyOf(rules);
    }

    public record ChunkBreakRule(ResourceKey<Level> dimension, BlockPos sourcePos, int radius, BlockState state) {
        public static final Codec<ChunkBreakRule> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(ChunkBreakRule::dimension),
                BlockPos.CODEC.fieldOf("sourcePos").forGetter(ChunkBreakRule::sourcePos),
                Codec.INT.fieldOf("radius").forGetter(ChunkBreakRule::radius),
                BlockState.CODEC.fieldOf("state").forGetter(ChunkBreakRule::state)
        ).apply(instance, ChunkBreakRule::new));

        public ChunkBreakRule {
            sourcePos = sourcePos.immutable();
        }

        public static ChunkBreakRule from(ServerLevel level, BlockPos sourcePos, int radius, BlockState state) {
            return new ChunkBreakRule(level.dimension(), sourcePos, radius, state);
        }

        public boolean appliesTo(ResourceKey<Level> targetDimension, ChunkPos chunkPos) {
            if (!dimension.equals(targetDimension)) {
                return false;
            }

            return chunkPos.x() >= minChunkX()
                    && chunkPos.x() <= maxChunkX()
                    && chunkPos.z() >= minChunkZ()
                    && chunkPos.z() <= maxChunkZ();
        }

        public boolean sameBreakSlot(ChunkBreakRule other) {
            return dimension.equals(other.dimension)
                    && sourcePos.getY() == other.sourcePos.getY()
                    && (sourcePos.getX() & 15) == (other.sourcePos.getX() & 15)
                    && (sourcePos.getZ() & 15) == (other.sourcePos.getZ() & 15)
                    && state.getBlock() == other.state.getBlock();
        }

        public boolean matchesSlotAndBlock(ResourceKey<Level> targetDimension, BlockPos pos, BlockState currentState) {
            return dimension.equals(targetDimension)
                    && sourcePos.getY() == pos.getY()
                    && (sourcePos.getX() & 15) == (pos.getX() & 15)
                    && (sourcePos.getZ() & 15) == (pos.getZ() & 15)
                    && state.getBlock() == currentState.getBlock();
        }

        public BlockPos targetPos(ChunkPos chunkPos) {
            return chunkPos.getBlockAt(sourcePos.getX() & 15, sourcePos.getY(), sourcePos.getZ() & 15);
        }

        private int minChunkX() {
            return SectionPos.blockToSectionCoord(sourcePos.getX() - radius);
        }

        private int maxChunkX() {
            return SectionPos.blockToSectionCoord(sourcePos.getX() + radius);
        }

        private int minChunkZ() {
            return SectionPos.blockToSectionCoord(sourcePos.getZ() - radius);
        }

        private int maxChunkZ() {
            return SectionPos.blockToSectionCoord(sourcePos.getZ() + radius);
        }
    }
}
