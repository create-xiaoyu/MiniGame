package com.xiaoyu.minigame.gamefeature.common.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class CommonConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue ENABLE_DEBUG_LOGS;
    public static final ModConfigSpec.IntValue MAX_PENDING_TICKS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        ENABLE_DEBUG_LOGS = builder
                .comment(
                        "Enables verbose debug logging for MiniGame internals.",
                        "Keep this disabled on normal servers unless you are diagnosing a bug; some logs can be very noisy in large worlds."
                )
                .define("enableDebugLogs", false);

        MAX_PENDING_TICKS = builder
                .comment(
                        "Maximum number of ticks to keep a chunk in the pending-loaded state after a chunk load event.",
                        "The chunk will be checked once per server tick. If it is not confirmed as loaded within this time, it will be discarded from the tracker.",
                        "This prevents invalid or never-ready chunks from staying in memory forever.",
                        "20 ticks = 1 second."
                )
                .comment("")
                .comment(
                        "区块 Load 事件触发后，最多保留在 pending 状态中的 tick 数。",
                        "系统会在每个服务端 tick 检查一次该区块是否已经真正可用。",
                        "如果在这个时间内仍未确认加载完成，则会放弃追踪该区块。",
                        "这样可以避免无效或永远未就绪的区块一直留在内存中。",
                        "20 ticks = 1 秒。"
                )
                .comment("")
                .defineInRange("maxPendingTicks", 20, 1, Integer.MAX_VALUE);

        SPEC = builder.build();
    }
}
