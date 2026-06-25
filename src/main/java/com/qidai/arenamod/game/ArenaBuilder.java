package com.qidai.arenamod.game;

import com.qidai.arenamod.ArenaMod;
import com.qidai.arenamod.config.ArenaConfig;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * 竞技场建造器
 * 生成 80×80（单人）或 30×30（PVP）的竞技场
 * PVP竞技场：双层基岩地基+虚空，无围墙/天花板
 */
public class ArenaBuilder {
    public static int getArenaHalf() { return ArenaConfig.getInstance().getArenaHalf(); }
    public static int getArenaHeight() { return ArenaConfig.getInstance().getArenaHeight(); }
    public static int getPvpArenaHalf() { return ArenaConfig.getInstance().getPvpArenaHalf(); }
    public static int getPvpArenaHeight() { return ArenaConfig.getInstance().getPvpArenaHeight(); }

    private static final int FLOOR_Y = 0;               // 地基Y坐标

    /**
     * 在指定世界以中心位置构建单人模式竞技场
     */
    public static void buildArena(ServerWorld world, BlockPos center) {
        buildRectArena(world, center, getArenaHalf(), getArenaHeight());
    }

    /**
     * 在指定世界以中心位置构建PVP竞技场
     * 双层基岩地基，虚空包围，无围墙/天花板
     */
    public static void buildPvpArena(ServerWorld world, BlockPos center) {
        int cx = center.getX();
        int cz = center.getZ();
        int half = getPvpArenaHalf();
        int height = getPvpArenaHeight();

        ArenaMod.LOGGER.info("开始建造PVP竞技场，中心: {}, 大小: {}×{}", center, half * 2, half * 2);

        // 1. 清除区域内所有旧方块
        for (int x = cx - half; x <= cx + half; x++) {
            for (int z = cz - half; z <= cz + half; z++) {
                for (int y = 0; y <= height; y++) {
                    world.setBlockState(new BlockPos(x, y, z), Blocks.AIR.getDefaultState());
                }
            }
        }

        // 2. 铺设双层基岩地基 (y=0 和 y=1)
        BlockState bedrock = Blocks.BEDROCK.getDefaultState();
        for (int x = cx - half; x <= cx + half; x++) {
            for (int z = cz - half; z <= cz + half; z++) {
                world.setBlockState(new BlockPos(x, 0, z), bedrock);
                world.setBlockState(new BlockPos(x, 1, z), bedrock);
            }
        }

        // 3. 在竞技场南方搭建观战台
        buildPvpSpectatorPlatform(world, center, half);

        ArenaMod.LOGGER.info("PVP竞技场建造完成，大小: {}×{}", half * 2, half * 2);
    }

    /**
     * 在竞技场南方搭建观战台
     * 位置：边界以南3格，10×5的阶梯状石英平台，铺设红毯
     */
    public static void buildPvpSpectatorPlatform(ServerWorld world, BlockPos center, int half) {
        int cx = center.getX();
        int cz = center.getZ();
        int platformStartZ = cz + half + 3;  // 边界以南3格

        BlockState quartz = Blocks.QUARTZ_BLOCK.getDefaultState();
        BlockState redCarpet = Blocks.RED_CARPET.getDefaultState();

        // 5行阶梯（从北到南逐级升高）
        for (int row = 0; row < 5; row++) {
            int z = platformStartZ + row;
            int yBase = 2 + row;  // 从y=2开始，每行+1

            // 每行10格宽 (X方向)
            for (int x = cx - 5; x <= cx + 4; x++) {
                // 石英块主体
                world.setBlockState(new BlockPos(x, yBase, z), quartz);
                // 红毯铺面
                world.setBlockState(new BlockPos(x, yBase + 1, z), redCarpet);
            }
        }
    }

    /**
     * 通用竞技场建造
     */
    private static void buildRectArena(ServerWorld world, BlockPos center, int half, int height) {
        ArenaMod.LOGGER.info("开始建造竞技场，中心: {}, 大小: {}×{}", center, half * 2, half * 2);

        int cx = center.getX();
        int cz = center.getZ();

        // 1. 平整地基 - 在 FLOOR_Y 处铺设基岩
        BlockState bedrock = Blocks.BEDROCK.getDefaultState();
        for (int x = cx - half; x <= cx + half; x++) {
            for (int z = cz - half; z <= cz + half; z++) {
                world.setBlockState(new BlockPos(x, FLOOR_Y, z), bedrock);
                // 清除地基以上的所有方块（重置区域）
                for (int y = FLOOR_Y + 1; y <= height; y++) {
                    world.setBlockState(new BlockPos(x, y, z), Blocks.AIR.getDefaultState());
                }
            }
        }

        // 2. 建造边界围墙（四个边），从 FLOOR_Y 到 height
        BlockState barrier = Blocks.BARRIER.getDefaultState();
        // 北墙 (z = cz - half)
        for (int x = cx - half; x <= cx + half; x++) {
            for (int y = FLOOR_Y; y <= height; y++) {
                world.setBlockState(new BlockPos(x, y, cz - half), barrier);
            }
        }
        // 南墙 (z = cz + half)
        for (int x = cx - half; x <= cx + half; x++) {
            for (int y = FLOOR_Y; y <= height; y++) {
                world.setBlockState(new BlockPos(x, y, cz + half), barrier);
            }
        }
        // 西墙 (x = cx - half)
        for (int z = cz - half + 1; z <= cz + half - 1; z++) {
            for (int y = FLOOR_Y; y <= height; y++) {
                world.setBlockState(new BlockPos(cx - half, y, z), barrier);
            }
        }
        // 东墙 (x = cx + half)
        for (int z = cz - half + 1; z <= cz + half - 1; z++) {
            for (int y = FLOOR_Y; y <= height; y++) {
                world.setBlockState(new BlockPos(cx + half, y, z), barrier);
            }
        }

        // 3. 天花板（限高处封顶）
        for (int x = cx - half; x <= cx + half; x++) {
            for (int z = cz - half; z <= cz + half; z++) {
                world.setBlockState(new BlockPos(x, height, z), barrier);
            }
        }

        ArenaMod.LOGGER.info("竞技场建造完成");
    }

    /**
     * 移除PVP竞技场地板（双层基岩全部移除）
     */
    public static void removeFloor(ServerWorld world, BlockPos center, int half) {
        BlockState air = Blocks.AIR.getDefaultState();
        int cx = center.getX();
        int cz = center.getZ();
        for (int x = cx - half + 1; x <= cx + half - 1; x++) {
            for (int z = cz - half + 1; z <= cz + half - 1; z++) {
                world.setBlockState(new BlockPos(x, 0, z), air);
                world.setBlockState(new BlockPos(x, 1, z), air);
            }
        }
    }

    /**
     * 恢复PVP竞技场地板（双层基岩全部恢复）
     */
    public static void restoreFloor(ServerWorld world, BlockPos center, int half) {
        BlockState bedrock = Blocks.BEDROCK.getDefaultState();
        int cx = center.getX();
        int cz = center.getZ();
        for (int x = cx - half + 1; x <= cx + half - 1; x++) {
            for (int z = cz - half + 1; z <= cz + half - 1; z++) {
                world.setBlockState(new BlockPos(x, 0, z), bedrock);
                world.setBlockState(new BlockPos(x, 1, z), bedrock);
            }
        }
    }

    /**
     * 清除竞技场内所有非结构性的玩家放置方块
     */
    public static void clearNonStructuralBlocks(ServerWorld world, BlockPos center, int half, int height) {
        BlockState air = Blocks.AIR.getDefaultState();
        int cx = center.getX();
        int cz = center.getZ();
        BlockState barrier = Blocks.BARRIER.getDefaultState();
        BlockState bedrock = Blocks.BEDROCK.getDefaultState();

        for (int x = cx - half + 1; x <= cx + half - 1; x++) {
            for (int z = cz - half + 1; z <= cz + half - 1; z++) {
                for (int y = FLOOR_Y + 1; y < height; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (!state.isAir() && !state.equals(barrier) && !state.equals(bedrock)) {
                        world.setBlockState(pos, air);
                    }
                }
            }
        }
    }

    /**
     * 检查坐标是否在竞技场范围内
     */
    public static boolean isInBounds(BlockPos pos, BlockPos center, int half) {
        int dx = Math.abs(pos.getX() - center.getX());
        int dz = Math.abs(pos.getZ() - center.getZ());
        return dx <= half && dz <= half;
    }

    public static int getFloorY() {
        return FLOOR_Y;
    }

    // ===== 商人 NPC =====

    /**
     * 在指定位置生成商人 NPC
     */
    private static VillagerEntity spawnVillager(ServerWorld world, BlockPos pos, Text name) {
        VillagerEntity villager = EntityType.VILLAGER.create(world);
        if (villager != null) {
            villager.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            villager.setCustomName(name);
            villager.setCustomNameVisible(true);
            villager.setInvulnerable(true);
            villager.setAiDisabled(true);
            villager.setSilent(true);
            villager.setPersistent();
            villager.setNoGravity(true);
            world.spawnEntity(villager);
            ArenaMod.LOGGER.info("{}已生成在 {}", name, pos);
        }
        return villager;
    }

    /**
     * 在指定位置生成物品商人 NPC
     */
    public static VillagerEntity spawnMerchant(ServerWorld world, BlockPos pos) {
        return spawnVillager(world, pos, Text.translatable("merchant.arenamod.merchant_name.item"));
    }

    /**
     * 在指定位置生成方块商人 NPC
     */
    public static VillagerEntity spawnBlockMerchant(ServerWorld world, BlockPos pos) {
        return spawnVillager(world, pos, Text.translatable("merchant.arenamod.merchant_name.block"));
    }

    /**
     * 在指定位置生成药水商人 NPC
     */
    public static VillagerEntity spawnPotionMerchant(ServerWorld world, BlockPos pos) {
        return spawnVillager(world, pos, Text.translatable("merchant.arenamod.merchant_name.potion"));
    }

    /**
     * 通过UUID移除指定商人
     */
    public static void removeMerchantByUuid(ServerWorld world, UUID uuid) {
        if (uuid == null) return;
        var entity = world.getEntity(uuid);
        if (entity != null) entity.discard();
    }

    /**
     * 移除所有商人（通过GameInstance获取UUID）
     */
    public static void removeAllMerchants(ServerWorld world, GameInstance game) {
        removeMerchantByUuid(world, game.getMerchantUuid(MerchantData.MERCHANT_TYPE_ITEM));
        removeMerchantByUuid(world, game.getMerchantUuid(MerchantData.MERCHANT_TYPE_BLOCK));
        removeMerchantByUuid(world, game.getMerchantUuid(MerchantData.MERCHANT_TYPE_POTION));
    }

    /**
     * 清空整个竞技场区域（游戏结束时调用，防止世界文件膨胀）
     */
    public static void clearArena(ServerWorld world, BlockPos center, int half, int height) {
        int cx = center.getX();
        int cz = center.getZ();
        BlockState air = Blocks.AIR.getDefaultState();

        for (int x = cx - half; x <= cx + half; x++) {
            for (int z = cz - half; z <= cz + half; z++) {
                for (int y = 0; y <= height; y++) {
                    world.setBlockState(new BlockPos(x, y, z), air);
                }
            }
        }
        ArenaMod.LOGGER.info("Arena cleared at center={} half={}", center, half);
    }

    /** 清空 PVP 竞技场 */
    public static void clearPvpArena(ServerWorld world, BlockPos center, int half, int height) {
        clearArena(world, center, half, height);
    }
}
