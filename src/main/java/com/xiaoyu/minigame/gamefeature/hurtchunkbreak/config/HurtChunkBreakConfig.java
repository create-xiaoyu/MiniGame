package com.xiaoyu.minigame.gamefeature.hurtchunkbreak.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class HurtChunkBreakConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLED;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        ENABLED = builder
                .comment("Enables hurt chunk break feature.")
                .define("enabled", true);

        SPEC = builder.build();
    }
}
