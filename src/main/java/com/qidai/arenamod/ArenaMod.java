package com.qidai.arenamod;

import com.qidai.arenamod.block.ModBlocks;
import com.qidai.arenamod.config.ArenaConfig;
import com.qidai.arenamod.dimension.ModDimensions;
import com.qidai.arenamod.game.GameInstance;
import com.qidai.arenamod.game.GameManager;
import com.qidai.arenamod.game.MerchantData;
import com.qidai.arenamod.game.ModCommands;
import com.qidai.arenamod.item.ModItemGroup;
import com.qidai.arenamod.item.ModItems;
import com.qidai.arenamod.network.ModNetworking;
import net.fabricmc.fabric.api.dimension.v1.FabricDimensions;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ArenaMod implements ModInitializer {
    public static final String MOD_ID = "arenamod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Arena Mod 初始化中...");

        // 注册方块
        ModBlocks.register();

        // 注册物品
        ModItems.register();

        // 注册创造模式标签页
        ModItemGroup.register();

        // 注册维度
        ModDimensions.register();

        // 注册网络包（C2S）
        ModNetworking.registerC2SPackets();

        // 注册命令
        ModCommands.register();

        // 注册事件
        registerEvents();

        LOGGER.info("Arena Mod 初始化完成！");
    }

    private void registerEvents() {
        // 加载配置文件
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            ArenaConfig.getInstance().load(server.getRunDirectory().toPath());
        });

        // 每 tick 驱动任务调度器
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            GameManager.getInstance().tickScheduler();
        });

        // 玩家加入服务器时，如果在竞技场维度但没有活跃游戏，传送回主世界
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var player = handler.getPlayer();
            if (player != null && player.getWorld().getRegistryKey().equals(ModDimensions.ARENA_WORLD_KEY)) {
                if (!GameManager.getInstance().isPlayerInGame(player)) {
                    ServerWorld overworld = server.getWorld(World.OVERWORLD);
                    if (overworld != null) {
                        BlockPos spawnPos = overworld.getSpawnPos();
                        TeleportTarget target = new TeleportTarget(
                                new Vec3d(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5),
                                Vec3d.ZERO, 0, 0
                        );
                        FabricDimensions.teleport(player, overworld, target);
                    }
                }
            }
        });

        // 玩家断开连接时清理游戏状态
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            var player = handler.getPlayer();
            if (player != null) {
                GameManager.getInstance().onPlayerDisconnect(player);
            }
        });

        // 玩家复活/重生后重新加入处理
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            // 如果玩家在竞技场中死亡，结束游戏
            if (GameManager.getInstance().isPlayerInGame(newPlayer)) {
                GameManager.getInstance().onPlayerDeath(newPlayer);
            }
        });

        // 竞技场世界 tick - 处理 PVP 循环逻辑（地板消失/恢复、方块清除、越界检测）
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            if (world.getRegistryKey().equals(ModDimensions.ARENA_WORLD_KEY)) {
                GameManager.getInstance().tickArenaWorld(world);
            }
        });

        // 商人右键交互（物品商人和方块商人）
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient) return ActionResult.PASS;
            if (!world.getRegistryKey().equals(ModDimensions.ARENA_WORLD_KEY)) return ActionResult.PASS;

            if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
                GameInstance game = GameManager.getInstance().getGame(serverPlayer);
                if (game != null && game.getGameMode() == com.qidai.arenamod.game.GameMode.SINGLE_PLAYER) {
                    // 物品商人
                    if (entity.getUuid().equals(game.getMerchantUuid(MerchantData.MERCHANT_TYPE_ITEM))) {
                        GameManager.getInstance().openMerchant(serverPlayer, game, MerchantData.MERCHANT_TYPE_ITEM);
                        return ActionResult.SUCCESS;
                    }
                    // 方块商人
                    if (entity.getUuid().equals(game.getMerchantUuid(MerchantData.MERCHANT_TYPE_BLOCK))) {
                        GameManager.getInstance().openMerchant(serverPlayer, game, MerchantData.MERCHANT_TYPE_BLOCK);
                        return ActionResult.SUCCESS;
                    }
                    // 药水商人
                    if (entity.getUuid().equals(game.getMerchantUuid(MerchantData.MERCHANT_TYPE_POTION))) {
                        GameManager.getInstance().openMerchant(serverPlayer, game, MerchantData.MERCHANT_TYPE_POTION);
                        return ActionResult.SUCCESS;
                    }
                }
            }
            return ActionResult.PASS;
        });

        // 方块破坏追踪 - 移除玩家破坏的方块记录
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world.isClient) return;
            if (!world.getRegistryKey().equals(ModDimensions.ARENA_WORLD_KEY)) return;

            if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
                GameInstance game = GameManager.getInstance().getGame(serverPlayer);
                if (game != null && game.isPVP()) {
                    game.getPlacedBlocks().remove(pos);
                }
            }
        });
    }
}
