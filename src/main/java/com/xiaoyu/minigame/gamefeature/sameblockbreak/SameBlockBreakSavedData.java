package com.xiaoyu.minigame.gamefeature.sameblockbreak;

import com.mojang.serialization.Codec;
import com.xiaoyu.minigame.MiniGame;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class SameBlockBreakSavedData extends SavedData {
    private static final Codec<SameBlockBreakSavedData> CODEC = Codec.STRING.listOf()
            .xmap(SameBlockBreakSavedData::new, SameBlockBreakSavedData::targetIdsForCodec);

    public static final SavedDataType<SameBlockBreakSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(MiniGame.MODID, "same_block_break"),
            SameBlockBreakSavedData::new,
            CODEC
    );

    private final Set<String> targetBlockIds = new LinkedHashSet<>();

    public SameBlockBreakSavedData() {
    }

    private SameBlockBreakSavedData(List<String> targetBlockIds) {
        for (String targetBlockId : targetBlockIds) {
            if (targetBlockId != null && !targetBlockId.isBlank()) {
                this.targetBlockIds.add(targetBlockId);
            }
        }
    }

    public boolean addTarget(Identifier blockId) {
        return this.addTarget(blockId.toString());
    }

    public boolean addTarget(String targetId) {
        boolean changed = this.targetBlockIds.add(targetId);
        if (changed) {
            this.setDirty();
        }
        return changed;
    }

    public boolean removeTarget(String blockId) {
        boolean changed = this.targetBlockIds.remove(blockId);
        if (changed) {
            this.setDirty();
        }
        return changed;
    }

    public int clearTargets() {
        int removed = this.targetBlockIds.size();
        if (removed > 0) {
            this.targetBlockIds.clear();
            this.setDirty();
        }
        return removed;
    }

    public Set<String> targetBlockIds() {
        return Set.copyOf(this.targetBlockIds);
    }

    public int targetCount() {
        return this.targetBlockIds.size();
    }

    private List<String> targetIdsForCodec() {
        return new ArrayList<>(this.targetBlockIds);
    }
}
