package com.qidai.arenamod.mixin;

import com.qidai.arenamod.config.ArenaConfig;
import com.qidai.arenamod.dimension.ModDimensions;
import com.qidai.arenamod.game.GameInstance;
import com.qidai.arenamod.game.GameManager;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 追踪玩家在竞技场中放置的方块（用于 PVP 每5分钟清除）
 * 限制PVP模式方块放置上限为80个
 */
@Mixin(BlockItem.class)
public class BlockPlacementMixin {

    @Inject(at = @At("HEAD"), method = "place", cancellable = true)
    private void onBeforePlace(ItemPlacementContext context, CallbackInfoReturnable<ActionResult> cir) {
        var world = context.getWorld();
        if (world.isClient) return;
        if (!world.getRegistryKey().equals(ModDimensions.ARENA_WORLD_KEY)) return;

        if (context.getPlayer() instanceof ServerPlayerEntity player) {
            GameInstance game = GameManager.getInstance().getGame(player);
            if (game != null && game.isPVP()) {
                if (game.getPlacedBlocks().size() >= ArenaConfig.getInstance().getPvpMaxPlacedBlocks()) {
                    player.sendMessage(Text.translatable("message.arenamod.block_limit_reached", ArenaConfig.getInstance().getPvpMaxPlacedBlocks()).formatted(Formatting.RED), false);
                    cir.setReturnValue(ActionResult.FAIL);
                }
            }
        }
    }

    @Inject(at = @At("RETURN"), method = "place")
    private void onPlace(ItemPlacementContext context, CallbackInfoReturnable<ActionResult> cir) {
        if (cir.getReturnValue() != ActionResult.SUCCESS) return;

        var world = context.getWorld();
        if (world.isClient) return;
        if (!world.getRegistryKey().equals(ModDimensions.ARENA_WORLD_KEY)) return;

        if (context.getPlayer() instanceof ServerPlayerEntity player) {
            GameInstance game = GameManager.getInstance().getGame(player);
            if (game != null && game.isPVP()) {
                BlockPos placedPos = context.getBlockPos();
                game.getPlacedBlocks().add(placedPos);
            }
        }
    }
}
