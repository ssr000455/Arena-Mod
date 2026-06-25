package com.qidai.arenamod.block;

import com.qidai.arenamod.game.GameManager;
import com.qidai.arenamod.network.ModNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class ArenaCoreBlock extends Block {
    public ArenaCoreBlock(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!world.isClient && player instanceof ServerPlayerEntity serverPlayer) {
            // 检查玩家是否已经在游戏中
            if (GameManager.getInstance().isPlayerInGame(serverPlayer)) {
                return ActionResult.FAIL;
            }
            // 发送打开模式选择界面的数据包
            ServerPlayNetworking.send(serverPlayer, ModNetworking.OPEN_MODE_SELECT_ID, PacketByteBufs.create());
        }
        return ActionResult.SUCCESS;
    }
}
