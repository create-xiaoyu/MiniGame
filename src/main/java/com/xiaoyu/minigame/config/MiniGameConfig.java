package com.xiaoyu.minigame.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class MiniGameConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("logging");
        DEBUG_LOGGING = builder
                .comment("Enable verbose MiniGame debug logs. Keep false during normal gameplay.")
                .define("debugLogging", false);
        builder.pop();

        SPEC = builder.build();
    }

    private MiniGameConfig() {
    }
}
