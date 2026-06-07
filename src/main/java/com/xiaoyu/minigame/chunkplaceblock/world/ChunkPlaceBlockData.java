package com.xiaoyu.minigame.chunkplaceblock.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.xiaoyu.minigame.MiniGame;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

public final class ChunkPlaceBlockData extends SavedData {
    private static final Identifier DATA_ID = Identifier.fromNamespaceAndPath(MiniGame.MODID, "chunk_place_block_rules");

    public static final Codec<ChunkPlaceBlockData> CODEC = ChunkPlaceRule.CODEC.listOf()
            .xmap(ChunkPlaceBlockData::new, ChunkPlaceBlockData::rulesForCodec);
    public static final SavedDataType<ChunkPlaceBlockData> TYPE = new SavedDataType<>(DATA_ID, ChunkPlaceBlockData::new, CODEC);

    private final ArrayList<ChunkPlaceRule> rules = new ArrayList<>();

    public ChunkPlaceBlockData() {
    }

    private ChunkPlaceBlockData(List<ChunkPlaceRule> rules) {
        this.rules.addAll(rules);
    }

    public static ChunkPlaceBlockData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public void addOrReplace(ChunkPlaceRule rule) {
        rules.removeIf(existing -> existing.samePlacementSlot(rule));
        rules.add(rule);
        setDirty();
    }

    public void removeMatchingPlacement(ServerLevel level, BlockPos sourcePos, BlockState state) {
        if (rules.removeIf(rule -> rule.matchesSlotAndBlock(level.dimension(), sourcePos, state))) {
            setDirty();
        }
    }

    public boolean hasManagedBlockEntity(ServerLevel level, BlockPos pos, BlockState state) {
        if (!state.hasBlockEntity()) {
            return false;
        }

        ChunkPos chunkPos = ChunkPos.containing(pos);
        for (ChunkPlaceRule rule : rules) {
            if (rule.matchesBlockEntitySource(level.dimension(), pos, chunkPos, state)) {
                return true;
            }
        }
        return false;
    }

    public boolean updateManagedBlockEntity(ServerLevel level, BlockPos pos, BlockState state, CompoundTag blockEntityTag) {
        boolean changed = false;
        ChunkPos chunkPos = ChunkPos.containing(pos);
        for (int i = 0; i < rules.size(); i++) {
            ChunkPlaceRule rule = rules.get(i);
            if (rule.matchesBlockEntitySource(level.dimension(), pos, chunkPos, state)) {
                rules.set(i, rule.withBlockEntityData(state, blockEntityTag));
                changed = true;
            }
        }

        if (changed) {
            setDirty();
        }
        return changed;
    }

    public List<ChunkPlaceRule> rulesForChunk(ServerLevel level, ChunkPos chunkPos) {
        ArrayList<ChunkPlaceRule> result = new ArrayList<>();
        for (ChunkPlaceRule rule : rules) {
            if (rule.appliesTo(level.dimension(), chunkPos)) {
                result.add(rule);
            }
        }
        return result;
    }

    private List<ChunkPlaceRule> rulesForCodec() {
        return List.copyOf(rules);
    }

    public record ChunkPlaceRule(
            ResourceKey<Level> dimension,
            BlockPos sourcePos,
            int radius,
            BlockState state,
            @Nullable CompoundTag blockEntityTag
    ) {
        public static final Codec<ChunkPlaceRule> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(ChunkPlaceRule::dimension),
                BlockPos.CODEC.fieldOf("sourcePos").forGetter(ChunkPlaceRule::sourcePos),
                Codec.INT.fieldOf("radius").forGetter(ChunkPlaceRule::radius),
                BlockState.CODEC.fieldOf("state").forGetter(ChunkPlaceRule::state),
                CompoundTag.CODEC.optionalFieldOf("blockEntityTag").forGetter(rule -> Optional.ofNullable(rule.blockEntityTag()))
        ).apply(instance, ChunkPlaceRule::fromCodec));

        public ChunkPlaceRule {
            sourcePos = sourcePos.immutable();
            blockEntityTag = blockEntityTag == null ? null : blockEntityTag.copy();
        }

        private static ChunkPlaceRule fromCodec(
                ResourceKey<Level> dimension,
                BlockPos sourcePos,
                int radius,
                BlockState state,
                Optional<CompoundTag> blockEntityTag
        ) {
            return new ChunkPlaceRule(dimension, sourcePos, radius, state, blockEntityTag.orElse(null));
        }

        public static ChunkPlaceRule from(ServerLevel level, BlockPos sourcePos, int radius, BlockState state, @Nullable CompoundTag blockEntityTag) {
            return new ChunkPlaceRule(level.dimension(), sourcePos, radius, state, blockEntityTag);
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

        public boolean samePlacementSlot(ChunkPlaceRule other) {
            return dimension.equals(other.dimension)
                    && sourcePos.getY() == other.sourcePos.getY()
                    && (sourcePos.getX() & 15) == (other.sourcePos.getX() & 15)
                    && (sourcePos.getZ() & 15) == (other.sourcePos.getZ() & 15);
        }

        public boolean matchesBlockEntitySource(ResourceKey<Level> targetDimension, BlockPos pos, ChunkPos chunkPos, BlockState currentState) {
            return appliesTo(targetDimension, chunkPos)
                    && currentState.getBlock() == state.getBlock()
                    && sourcePos.getY() == pos.getY()
                    && (sourcePos.getX() & 15) == (pos.getX() & 15)
                    && (sourcePos.getZ() & 15) == (pos.getZ() & 15);
        }

        public boolean matchesSlotAndBlock(ResourceKey<Level> targetDimension, BlockPos pos, BlockState currentState) {
            return dimension.equals(targetDimension)
                    && currentState.getBlock() == state.getBlock()
                    && sourcePos.getY() == pos.getY()
                    && (sourcePos.getX() & 15) == (pos.getX() & 15)
                    && (sourcePos.getZ() & 15) == (pos.getZ() & 15);
        }

        public ChunkPlaceRule withBlockEntityData(BlockState currentState, CompoundTag tag) {
            return new ChunkPlaceRule(dimension, sourcePos, radius, currentState, tag);
        }

        public BlockPos targetPos(ChunkPos chunkPos) {
            return chunkPos.getBlockAt(sourcePos.getX() & 15, sourcePos.getY(), sourcePos.getZ() & 15);
        }

        public boolean isSourceChunk(ChunkPos chunkPos) {
            return chunkPos.x() == SectionPos.blockToSectionCoord(sourcePos.getX())
                    && chunkPos.z() == SectionPos.blockToSectionCoord(sourcePos.getZ());
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
