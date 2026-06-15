package com.xiaoyu.minigame.gamefeature.chunkplaceblock;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.xiaoyu.minigame.MiniGame;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ChunkPlaceBlockBreakSavedData extends SavedData {
    private static final Codec<SavedBreakRule> BREAK_RULE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(0, 15).fieldOf("local_x").forGetter(SavedBreakRule::localX),
            Codec.INT.fieldOf("y").forGetter(SavedBreakRule::y),
            Codec.intRange(0, 15).fieldOf("local_z").forGetter(SavedBreakRule::localZ),
            BlockState.CODEC.fieldOf("state").forGetter(SavedBreakRule::state),
            Codec.BOOL.fieldOf("only_matching_block").forGetter(SavedBreakRule::onlyMatchingBlock),
            Codec.BOOL.fieldOf("exact_state").forGetter(SavedBreakRule::exactState)
    ).apply(instance, SavedBreakRule::new));

    private static final Codec<ChunkPlaceBlockBreakSavedData> CODEC = BREAK_RULE_CODEC.listOf()
            .xmap(ChunkPlaceBlockBreakSavedData::new, ChunkPlaceBlockBreakSavedData::rulesForCodec);

    public static final SavedDataType<ChunkPlaceBlockBreakSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(MiniGame.MODID, "chunk_place_block_breaks"),
            ChunkPlaceBlockBreakSavedData::new,
            CODEC
    );

    private final Map<Long, SavedBreakRule> rules = new LinkedHashMap<>();

    public ChunkPlaceBlockBreakSavedData() {
    }

    private ChunkPlaceBlockBreakSavedData(List<SavedBreakRule> rules) {
        for (SavedBreakRule rule : rules) {
            if (rule != null && !rule.state().isAir()) {
                this.rules.put(rule.localKey(), rule);
            }
        }
    }

    public boolean upsertRule(SavedBreakRule rule) {
        if (rule.state().isAir()) {
            return false;
        }

        SavedBreakRule previous = this.rules.put(rule.localKey(), rule);
        boolean changed = previous == null || !previous.equals(rule);
        if (changed) {
            this.setDirty();
        }
        return changed;
    }

    public int removeRulesAt(int localX, int y, int localZ) {
        int removed = 0;
        Iterator<Map.Entry<Long, SavedBreakRule>> iterator = this.rules.entrySet().iterator();
        while (iterator.hasNext()) {
            SavedBreakRule rule = iterator.next().getValue();
            if (!rule.matchesPosition(localX, y, localZ)) {
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

    public List<SavedBreakRule> rules() {
        return this.rules.values().stream().toList();
    }

    public int ruleCount() {
        return this.rules.size();
    }

    private List<SavedBreakRule> rulesForCodec() {
        return new ArrayList<>(this.rules.values());
    }

    public record SavedBreakRule(int localX, int y, int localZ, BlockState state, boolean onlyMatchingBlock, boolean exactState) {
        public SavedBreakRule {
            localX &= 15;
            localZ &= 15;
        }

        public static SavedBreakRule fromBreak(int localX, int y, int localZ, BlockState state, boolean onlyMatchingBlock, boolean exactState) {
            return new SavedBreakRule(localX, y, localZ, state, onlyMatchingBlock, exactState);
        }

        public boolean matchesPosition(int localX, int y, int localZ) {
            return this.localX == (localX & 15) && this.y == y && this.localZ == (localZ & 15);
        }

        public boolean shouldBreak(BlockState targetState) {
            if (targetState.isAir()) {
                return false;
            }
            return !this.onlyMatchingBlock || (this.exactState ? this.state == targetState : targetState.is(this.state.getBlock()));
        }

        private long localKey() {
            return ((long) this.y & 0xFFFFFFFFL) << 8 | (long) (this.localX & 15) << 4 | (long) (this.localZ & 15);
        }
    }
}
