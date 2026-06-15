package com.xiaoyu.minigame.gamefeature.chunkplaceblock;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.xiaoyu.minigame.MiniGame;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ChunkPlaceBlockSavedData extends SavedData {
    private static final Codec<SavedPlacementRule> RULE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(0, 15).fieldOf("local_x").forGetter(SavedPlacementRule::localX),
            Codec.INT.fieldOf("y").forGetter(SavedPlacementRule::y),
            Codec.intRange(0, 15).fieldOf("local_z").forGetter(SavedPlacementRule::localZ),
            BlockState.CODEC.fieldOf("state").forGetter(SavedPlacementRule::state),
            CompoundTag.CODEC.optionalFieldOf("block_entity").forGetter(rule -> Optional.ofNullable(rule.blockEntityTag()).map(CompoundTag::copy))
    ).apply(instance, (localX, y, localZ, state, blockEntityTag) ->
            new SavedPlacementRule(localX, y, localZ, state, blockEntityTag.map(CompoundTag::copy).orElse(null))));

    private static final Codec<ChunkPlaceBlockSavedData> CODEC = RULE_CODEC.listOf()
            .xmap(ChunkPlaceBlockSavedData::new, ChunkPlaceBlockSavedData::rulesForCodec);

    public static final SavedDataType<ChunkPlaceBlockSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(MiniGame.MODID, "chunk_place_block"),
            ChunkPlaceBlockSavedData::new,
            CODEC
    );

    private final Map<Long, SavedPlacementRule> rules = new LinkedHashMap<>();

    public ChunkPlaceBlockSavedData() {
    }

    private ChunkPlaceBlockSavedData(List<SavedPlacementRule> rules) {
        for (SavedPlacementRule rule : rules) {
            if (rule != null && !rule.state().isAir()) {
                this.rules.put(rule.localKey(), rule.copy());
            }
        }
    }

    public boolean upsertRule(SavedPlacementRule rule) {
        if (rule.state().isAir()) {
            return false;
        }

        SavedPlacementRule previous = this.rules.put(rule.localKey(), rule.copy());
        boolean changed = previous == null || !previous.equals(rule);
        if (changed) {
            this.setDirty();
        }
        return changed;
    }

    public int removeRulesForBreak(int localX, int y, int localZ, BlockState brokenState, boolean onlyMatchingBlock, boolean exactState) {
        int removed = 0;
        Iterator<Map.Entry<Long, SavedPlacementRule>> iterator = this.rules.entrySet().iterator();
        while (iterator.hasNext()) {
            SavedPlacementRule rule = iterator.next().getValue();
            if (!rule.matchesPosition(localX, y, localZ)) {
                continue;
            }
            if (onlyMatchingBlock && !rule.matchesState(brokenState, exactState)) {
                continue;
            }

            iterator.remove();
            removed++;
        }

        if (removed > 0) {
            this.setDirty();
        }
        return removed;
    }

    public int clearRules() {
        int removed = this.rules.size();
        if (removed > 0) {
            this.rules.clear();
            this.setDirty();
        }
        return removed;
    }

    public List<SavedPlacementRule> rules() {
        return this.rules.values().stream().map(SavedPlacementRule::copy).toList();
    }

    public int ruleCount() {
        return this.rules.size();
    }

    private List<SavedPlacementRule> rulesForCodec() {
        return new ArrayList<>(this.rules.values());
    }

    public record SavedPlacementRule(int localX, int y, int localZ, BlockState state, @Nullable CompoundTag blockEntityTag) {
        public SavedPlacementRule {
            localX &= 15;
            localZ &= 15;
            blockEntityTag = blockEntityTag == null ? null : blockEntityTag.copy();
        }

        public boolean matchesPosition(int localX, int y, int localZ) {
            return this.localX == (localX & 15) && this.y == y && this.localZ == (localZ & 15);
        }

        public boolean matchesState(BlockState other, boolean exactState) {
            return exactState ? this.state == other : this.state.is(other.getBlock());
        }

        public SavedPlacementRule copy() {
            return new SavedPlacementRule(this.localX, this.y, this.localZ, this.state, this.blockEntityTag);
        }

        private long localKey() {
            return ((long) this.y & 0xFFFFFFFFL) << 8 | (long) (this.localX & 15) << 4 | (long) (this.localZ & 15);
        }
    }
}
