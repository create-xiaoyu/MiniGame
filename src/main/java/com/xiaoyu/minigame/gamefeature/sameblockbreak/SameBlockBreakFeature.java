package com.xiaoyu.minigame.gamefeature.sameblockbreak;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.xiaoyu.minigame.MiniGame;
import com.xiaoyu.minigame.gamefeature.sameblockbreak.command.SameBlockBreakCommands;
import com.xiaoyu.minigame.gamefeature.sameblockbreak.config.SameBlockBreakConfig;
import com.xiaoyu.minigame.gamefeature.sameblockbreak.destruction.DestructionManager;
import com.xiaoyu.minigame.gamefeature.sameblockbreak.destruction.DestructionSettings;
import com.xiaoyu.minigame.gamefeature.sameblockbreak.world.ForbiddenBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class SameBlockBreakFeature {
    private static final Map<UUID, BucketTrigger> LAST_BUCKET_TRIGGERS = new HashMap<>();

    private SameBlockBreakFeature() {
    }

    public static void register(ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, SameBlockBreakConfig.SPEC, "minigame/sameblockbreak.toml");

        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, BreakBlockEvent.class, SameBlockBreakFeature::onBreakBlock);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ExplosionEvent.Detonate.class, SameBlockBreakFeature::onExplosionDetonate);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, LivingDestroyBlockEvent.class, SameBlockBreakFeature::onLivingDestroyBlock);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, PlayerInteractEvent.RightClickItem.class, SameBlockBreakFeature::onBucketRightClickItem);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, PlayerInteractEvent.RightClickBlock.class, SameBlockBreakFeature::onBucketRightClickBlock);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, SameBlockBreakFeature::onServerTick);
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, SameBlockBreakCommands::register);
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, SameBlockBreakFeature::onServerStarted);
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, SameBlockBreakFeature::onServerStopping);
    }

    private static void onBreakBlock(BreakBlockEvent event) {
        if (event.isCanceled() || DestructionManager.isInternalBreak()) {
            return;
        }

        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }

        DestructionManager.INSTANCE.start(level, player, event.getState(), event.getPos());
    }

    private static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (DestructionManager.isInternalBreak()) {
            return;
        }

        if (!(event.getLevel() instanceof ServerLevel level)
                || event.getExplosion().getBlockInteraction() == Explosion.BlockInteraction.KEEP) {
            return;
        }

        Entity actor = event.getExplosion().getIndirectSourceEntity();
        if (actor == null) {
            actor = event.getExplosion().getDirectSourceEntity();
        }

        DestructionSettings settings = DestructionSettings.fromConfig();
        if (!settings.enabled()) {
            return;
        }

        Set<Block> triggeredBlocks = new HashSet<>();
        for (BlockPos pos : event.getAffectedBlocks()) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || !triggeredBlocks.add(state.getBlock())) {
                continue;
            }

            DestructionManager.INSTANCE.start(level, actor, state, pos);
            if (!settings.allowConcurrentTasks()) {
                return;
            }
        }
    }

    private static void onLivingDestroyBlock(LivingDestroyBlockEvent event) {
        if (event.isCanceled() || DestructionManager.isInternalBreak()) {
            return;
        }

        if (!(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }

        DestructionManager.INSTANCE.start(level, event.getEntity(), event.getState(), event.getPos());
    }

    private static void onBucketRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!event.isCanceled()) {
            tryStartBucketFluidPickup(event);
        }
    }

    private static void onBucketRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.isCanceled()) {
            tryStartBucketFluidPickup(event);
        }
    }

    private static void tryStartBucketFluidPickup(PlayerInteractEvent event) {
        if (DestructionManager.isInternalBreak() || event.getItemStack().getItem() != Items.BUCKET) {
            return;
        }

        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        BlockHitResult hitResult = Item.getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
        if (hitResult.getType() != HitResult.Type.BLOCK) {
            return;
        }

        BlockPos pos = hitResult.getBlockPos();
        if (!level.mayInteract(player, pos)
                || !player.mayUseItemAt(pos.relative(hitResult.getDirection()), hitResult.getDirection(), event.getItemStack())) {
            return;
        }

        FluidState fluidState = level.getFluidState(pos);
        if (!fluidState.isSourceOfType(Fluids.WATER) && !fluidState.isSourceOfType(Fluids.LAVA)) {
            return;
        }

        if (isDuplicateBucketTrigger(level, player, event.getHand(), pos)) {
            return;
        }

        DestructionManager.INSTANCE.start(level, player, fluidState.createLegacyBlock(), pos);
    }

    private static boolean isDuplicateBucketTrigger(ServerLevel level, ServerPlayer player, InteractionHand hand, BlockPos pos) {
        BucketTrigger current = new BucketTrigger(level.getGameTime(), hand, pos.immutable());
        BucketTrigger previous = LAST_BUCKET_TRIGGERS.put(player.getUUID(), current);
        return current.equals(previous);
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        DestructionManager.INSTANCE.tick(event.getServer());
    }

    private static void onServerStarted(ServerStartedEvent event) {
        ForbiddenBlocks.get(event.getServer());
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        LAST_BUCKET_TRIGGERS.clear();
        ForbiddenBlocks.clearActiveCache();
        int cancelled = DestructionManager.INSTANCE.cancelAll();
        if (cancelled > 0) {
            MiniGame.LOGGER.info("Cancelled {} SameBlockBreak task(s) during server shutdown", cancelled);
        }
    }

    private record BucketTrigger(long gameTime, InteractionHand hand, BlockPos pos) {
    }
}
