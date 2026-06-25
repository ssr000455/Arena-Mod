package com.qidai.arenamod.wave;

import com.qidai.arenamod.game.GameInstance;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 波次刷怪器 - 负责在竞技场内生成怪物并追踪 EXP
 */
public class WaveSpawner {
    private static final Random RANDOM = new Random();
    private static final int MIN_SPAWN_DISTANCE = 10;

    // UUID → EXP 值追踪表（用于怪物死亡时发放经验点）
    private static final Map<UUID, Integer> EXP_TRACKER = new ConcurrentHashMap<>();

    /**
     * 生成指定批次的所有怪物
     */
    public static List<MobEntity> spawnBatch(ServerWorld world, GameInstance game, WaveDefinition.Batch batch) {
        List<MobEntity> spawned = new ArrayList<>();
        int arenaHalf = game.getArenaHalf();

        for (WaveDefinition.SpawnGroup group : batch.groups()) {
            for (char code : group.codes()) {
                int count = group.countPerCode();
                MonsterCodes.MonsterEntry entry = MonsterCodes.get(code);
                int exp = entry.expPoints();
                for (int i = 0; i < count; i++) {
                    BlockPos spawnPos = findSpawnPosition(world, game.getArenaCenter(), arenaHalf);
                    if (spawnPos != null) {
                        MobEntity entity = entry.create(world, spawnPos);
                        if (entity != null) {
                            // 设置婴儿僵尸
                            if (code == 'S' && entity instanceof net.minecraft.entity.mob.ZombieEntity zombie) {
                                zombie.setBaby(true);
                            }
                            world.spawnEntity(entity);
                            // 追踪 EXP
                            EXP_TRACKER.put(entity.getUuid(), exp);
                            spawned.add(entity);
                        }
                    }
                }
            }
        }
        return spawned;
    }

    /**
     * 获取怪物对应的 EXP 值
     */
    public static int getExpValue(UUID entityUuid) {
        return EXP_TRACKER.getOrDefault(entityUuid, 0);
    }

    /**
     * 移除 EXP 追踪（怪物已死亡或游戏结束）
     */
    public static void removeTracking(UUID entityUuid) {
        EXP_TRACKER.remove(entityUuid);
    }

    /**
     * 清除所有追踪（游戏结束）
     */
    public static void clearAllTracking() {
        EXP_TRACKER.clear();
    }

    private static BlockPos findSpawnPosition(ServerWorld world, BlockPos center, int arenaHalf) {
        for (int attempt = 0; attempt < 20; attempt++) {
            int x = center.getX() + RANDOM.nextInt(arenaHalf * 2) - arenaHalf;
            int z = center.getZ() + RANDOM.nextInt(arenaHalf * 2) - arenaHalf;

            double dist = Math.sqrt(Math.pow(x - center.getX(), 2) + Math.pow(z - center.getZ(), 2));
            if (dist < MIN_SPAWN_DISTANCE) continue;

            int y = center.getY();
            BlockPos pos = new BlockPos(x, y, z);

            if (world.getBlockState(pos).isAir() && !world.getBlockState(pos.down()).isAir()) {
                return pos;
            }
            for (int dy = 1; dy <= 3; dy++) {
                BlockPos checkPos = pos.up(dy);
                if (world.getBlockState(checkPos).isAir() && !world.getBlockState(checkPos.down()).isAir()) {
                    return checkPos;
                }
            }
        }
        // 保底：在中心附近寻找一个安全位置
        for (int attempt = 0; attempt < 10; attempt++) {
            BlockPos fallbackPos = center.add(
                    RANDOM.nextInt(arenaHalf * 2) - arenaHalf, 0,
                    RANDOM.nextInt(arenaHalf * 2) - arenaHalf
            ).up(1);
            if (world.getBlockState(fallbackPos).isAir()) {
                return fallbackPos;
            }
        }
        return center.up(1);
    }

    public static boolean isBatchCleared(List<MobEntity> currentBatchMobs) {
        return currentBatchMobs.stream().allMatch(m -> !m.isAlive());
    }
}
