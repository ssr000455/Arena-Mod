package com.qidai.arenamod.wave;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * 怪物代号映射表 + 经验点数值
 */
public class MonsterCodes {
    private static final Map<Character, MonsterEntry> CODE_MAP = new HashMap<>();

    public record MonsterEntry(EntityType<? extends MobEntity> type, String name, int expPoints) {
        public MobEntity create(ServerWorld world, BlockPos pos) {
            MobEntity entity = (MobEntity) type.create(world, null, null, pos, SpawnReason.COMMAND, false, false);
            if (entity != null) {
                entity.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                entity.setPersistent();
            }
            return entity;
        }
    }

    static {
        // ===== 普通（1点）=====
        map('A', EntityType.ZOMBIE, "僵尸", 1);
        map('B', EntityType.SKELETON, "小白", 1);
        map('C', EntityType.CREEPER, "苦力怕", 1);
        map('D', EntityType.SPIDER, "蜘蛛", 1);
        map('F', EntityType.ENDERMAN, "末影人", 1);
        map('H', EntityType.VEX, "怨灵", 1);
        map('I', EntityType.VINDICATOR, "掠夺者(斧)", 1);
        map('J', EntityType.PILLAGER, "掠夺者(弩)", 1);
        map('K', EntityType.MAGMA_CUBE, "岩浆怪", 1);
        map('L', EntityType.ZOMBIFIED_PIGLIN, "下界猪兽", 2);
        map('M', EntityType.ZOMBIE_VILLAGER, "僵尸猎人", 1);
        map('N', EntityType.DROWNED, "溺尸", 1);
        map('O', EntityType.PILLAGER, "掠夺者队长", 1);
        map('S', EntityType.ZOMBIE, "小僵尸", 1);  // 婴儿僵尸在 spawn 时设置
        map('T', EntityType.HUSK, "尸壳", 1);
        map('U', EntityType.SHULKER, "潜伏影怪", 1);
        map('V', EntityType.PHANTOM, "幻翼", 1);
        map('W', EntityType.CAVE_SPIDER, "洞穴蜘蛛", 1);
        map('X', EntityType.SPIDER, "蜘蛛", 1);
        map('Z', EntityType.SLIME, "史莱姆", 1);
        map('ǔ', EntityType.ENDERMITE, "末影螨", 1);
        map('△', EntityType.WITCH, "女巫", 1);
        map('☆', EntityType.WITHER_SKELETON, "凋零小白", 1);
        map('◍', EntityType.SILVERFISH, "蠹虫", 1);

        // ===== 强力（2点）=====
        map('P', EntityType.RAVAGER, "劫掠兽", 2);
        map('Q', EntityType.EVOKER, "尖刺法师", 2);
        map('◙', EntityType.EVOKER, "幻魔者", 2);
        map('◇', EntityType.GHAST, "恶魂", 2);

        // ===== 极强（3点）=====
        map('G', EntityType.ENDER_DRAGON, "末影龙", 3);
        map('E', EntityType.WARDEN, "寻声守卫者", 3);
        map('Y', EntityType.WITHER, "凋灵", 3);
        map('R', EntityType.WITHER_SKELETON, "凋零", 3);
    }

    private static void map(char code, EntityType<?> type, String name, int exp) {
        CODE_MAP.put(code, new MonsterEntry((EntityType<? extends MobEntity>) type, name, exp));
    }

    public static MonsterEntry get(char code) {
        MonsterEntry entry = CODE_MAP.get(code);
        if (entry == null) {
            throw new IllegalArgumentException("未知的怪物代号: " + code);
        }
        return entry;
    }

    public static boolean isValid(char code) {
        return CODE_MAP.containsKey(code);
    }

    public static int getExpPoints(char code) {
        MonsterEntry entry = CODE_MAP.get(code);
        return entry != null ? entry.expPoints() : 0;
    }
}
