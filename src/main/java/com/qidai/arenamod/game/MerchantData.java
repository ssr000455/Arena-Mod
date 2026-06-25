package com.qidai.arenamod.game;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.potion.PotionUtil;
import net.minecraft.potion.Potions;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.UUID;

/**
 * 竞技场商人数据：商品、价格、升级
 */
public class MerchantData {

    /** Upgrade costs are read from ArenaConfig (merchant.upgradeCostTier1 / tier2) */

    /** 商人类型常量 */
    public static final int MERCHANT_TYPE_ITEM = 0;
    public static final int MERCHANT_TYPE_BLOCK = 1;
    public static final int MERCHANT_TYPE_POTION = 2;

    public record MerchantItem(ItemStack item, int expCost, String category) {}

    // ========== 物品商人（原商人） ==========

    /** 一级商品 */
    public static final List<MerchantItem> TIER_1 = List.of(
            // 武器 (10 exp)
            new MerchantItem(new ItemStack(Items.WOODEN_SWORD), 10, "weapon"),
            new MerchantItem(new ItemStack(Items.WOODEN_AXE), 10, "weapon"),
            new MerchantItem(new ItemStack(Items.BOW), 10, "weapon"),
            new MerchantItem(new ItemStack(Items.ARROW, 16), 10, "weapon"),
            new MerchantItem(new ItemStack(Items.CROSSBOW), 10, "weapon"),
            new MerchantItem(new ItemStack(Items.SHIELD), 10, "weapon"),
            // 食物 (5 exp)
            new MerchantItem(new ItemStack(Items.CARROT, 8), 5, "food"),
            new MerchantItem(new ItemStack(Items.APPLE, 8), 5, "food"),
            new MerchantItem(new ItemStack(Items.PUMPKIN_PIE, 4), 5, "food"),
            new MerchantItem(new ItemStack(Items.MELON_SLICE, 16), 5, "food"),
            new MerchantItem(new ItemStack(Items.SWEET_BERRIES, 8), 5, "food"),
            new MerchantItem(new ItemStack(Items.POISONOUS_POTATO, 2), 5, "food"),
            new MerchantItem(new ItemStack(Items.ROTTEN_FLESH, 4), 5, "food"),
            new MerchantItem(new ItemStack(Items.COOKED_BEEF, 8), 5, "food"),
            new MerchantItem(new ItemStack(Items.COOKED_MUTTON, 8), 5, "food"),
            new MerchantItem(new ItemStack(Items.COOKED_CHICKEN, 8), 5, "food"),
            // 防具 (15 exp) — 皮革套
            new MerchantItem(new ItemStack(Items.LEATHER_HELMET), 15, "armor"),
            new MerchantItem(new ItemStack(Items.LEATHER_CHESTPLATE), 15, "armor"),
            new MerchantItem(new ItemStack(Items.LEATHER_LEGGINGS), 15, "armor"),
            new MerchantItem(new ItemStack(Items.LEATHER_BOOTS), 15, "armor")
    );

    /** 二级商品 */
    public static final List<MerchantItem> TIER_2 = List.of(
            // 装备 (40 exp)
            new MerchantItem(new ItemStack(Items.IRON_HELMET), 40, "armor"),
            new MerchantItem(new ItemStack(Items.IRON_CHESTPLATE), 40, "armor"),
            new MerchantItem(new ItemStack(Items.IRON_LEGGINGS), 40, "armor"),
            new MerchantItem(new ItemStack(Items.IRON_BOOTS), 40, "armor"),
            // 工具 (25 exp)
            new MerchantItem(new ItemStack(Items.WOODEN_PICKAXE), 25, "tool"),
            new MerchantItem(new ItemStack(Items.FIRE_CHARGE, 8), 25, "tool"),
            // 食物 (25 exp)
            new MerchantItem(new ItemStack(Items.COOKED_BEEF, 8), 25, "food"),
            new MerchantItem(new ItemStack(Items.COOKED_MUTTON, 8), 25, "food"),
            new MerchantItem(new ItemStack(Items.COOKED_CHICKEN, 8), 25, "food"),
            new MerchantItem(new ItemStack(Items.GOLDEN_APPLE, 2), 25, "food"),
            new MerchantItem(new ItemStack(Items.GOLDEN_CARROT, 8), 25, "food"),
            new MerchantItem(new ItemStack(Items.BREAD, 16), 25, "food"),
            new MerchantItem(new ItemStack(Items.DRIED_KELP, 16), 25, "food"),
            new MerchantItem(new ItemStack(Items.CAKE), 25, "food"),
            new MerchantItem(new ItemStack(Items.CARVED_PUMPKIN), 25, "food"),
            new MerchantItem(new ItemStack(Items.MILK_BUCKET), 25, "food")
    );

    /** 三级商品 */
    public static final List<MerchantItem> TIER_3 = List.of(
            // 装备 (50 exp)
            new MerchantItem(new ItemStack(Items.DIAMOND_HELMET), 50, "armor"),
            new MerchantItem(new ItemStack(Items.DIAMOND_CHESTPLATE), 50, "armor"),
            new MerchantItem(new ItemStack(Items.DIAMOND_LEGGINGS), 50, "armor"),
            new MerchantItem(new ItemStack(Items.DIAMOND_BOOTS), 50, "armor"),
            // 武器/箭矢 (40 exp)
            new MerchantItem(createTippedArrow(Potions.STRENGTH, 8), 40, "weapon"),
            new MerchantItem(createTippedArrow(Potions.SWIFTNESS, 8), 40, "weapon"),
            new MerchantItem(createTippedArrow(Potions.REGENERATION, 8), 40, "weapon"),
            new MerchantItem(createTippedArrow(Potions.POISON, 8), 40, "weapon"),
            new MerchantItem(createTippedArrow(Potions.HEALING, 8), 40, "weapon"),
            new MerchantItem(createTippedArrow(Potions.FIRE_RESISTANCE, 8), 40, "weapon"),
            // 食物 (40 exp)
            new MerchantItem(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE), 40, "food"),
            new MerchantItem(new ItemStack(Items.WATER_BUCKET), 40, "other"),
            // 通行证 (50 exp)
            new MerchantItem(createPass(), 50, "pass"),
            // 超级剑 (75 exp)
            new MerchantItem(createSuperSword(), 75, "weapon")
    );

    // ========== 方块商人 ==========

    /** 方块商人一级商品：各色羊毛、石头、圆石、草块、木头、煤块 (2 exp) */
    public static final List<MerchantItem> BLOCK_TIER_1 = List.of(
            new MerchantItem(new ItemStack(Items.WHITE_WOOL, 16), 2, "block"),
            new MerchantItem(new ItemStack(Items.ORANGE_WOOL, 16), 2, "block"),
            new MerchantItem(new ItemStack(Items.LIGHT_BLUE_WOOL, 16), 2, "block"),
            new MerchantItem(new ItemStack(Items.RED_WOOL, 16), 2, "block"),
            new MerchantItem(new ItemStack(Items.LIME_WOOL, 16), 2, "block"),
            new MerchantItem(new ItemStack(Items.BLUE_WOOL, 16), 2, "block"),
            new MerchantItem(new ItemStack(Items.BLACK_WOOL, 16), 2, "block"),
            new MerchantItem(new ItemStack(Items.GRAY_WOOL, 16), 2, "block"),
            new MerchantItem(new ItemStack(Items.PINK_WOOL, 16), 2, "block"),
            new MerchantItem(new ItemStack(Items.CYAN_WOOL, 16), 2, "block"),
            new MerchantItem(new ItemStack(Items.STONE, 16), 2, "block"),
            new MerchantItem(new ItemStack(Items.COBBLESTONE, 16), 2, "block"),
            new MerchantItem(new ItemStack(Items.GRASS_BLOCK, 8), 2, "block"),
            new MerchantItem(new ItemStack(Items.OAK_LOG, 16), 2, "block"),
            new MerchantItem(new ItemStack(Items.COAL_BLOCK, 8), 2, "block")
    );

    /** 方块商人二级商品：铁块、金块、钻石块、下界合金块、黑曜石、绿宝石块、石英块 (10 exp) */
    public static final List<MerchantItem> BLOCK_TIER_2 = List.of(
            new MerchantItem(new ItemStack(Items.IRON_BLOCK, 8), 10, "block"),
            new MerchantItem(new ItemStack(Items.GOLD_BLOCK, 8), 10, "block"),
            new MerchantItem(new ItemStack(Items.DIAMOND_BLOCK, 8), 10, "block"),
            new MerchantItem(new ItemStack(Items.NETHERITE_BLOCK, 4), 10, "block"),
            new MerchantItem(new ItemStack(Items.OBSIDIAN, 8), 10, "block"),
            new MerchantItem(new ItemStack(Items.EMERALD_BLOCK, 8), 10, "block"),
            new MerchantItem(new ItemStack(Items.QUARTZ_BLOCK, 8), 10, "block")
    );

    /** 方块商人三级商品：屏障、允许、边界、光源、水、流动岩浆 (25 exp) */
    public static final List<MerchantItem> BLOCK_TIER_3 = List.of(
            new MerchantItem(new ItemStack(Items.BARRIER), 25, "block"),
            new MerchantItem(new ItemStack(Items.STRUCTURE_VOID), 25, "block"),
            new MerchantItem(new ItemStack(Items.STRUCTURE_BLOCK), 25, "block"),
            new MerchantItem(new ItemStack(Items.LIGHT, 16), 25, "block"),
            new MerchantItem(new ItemStack(Items.WATER_BUCKET), 25, "block"),
            new MerchantItem(new ItemStack(Items.LAVA_BUCKET), 25, "block")
    );

    // ========== 药水商人 ==========

    /** 药水商人一级：普通药水 I级 (25 exp) */
    public static final List<MerchantItem> POTION_TIER_1 = List.of(
            createPotionItem(Items.POTION, Potions.SWIFTNESS, "merchant.arenamod.potion.swiftness", 25),
            createPotionItem(Items.POTION, Potions.STRENGTH, "merchant.arenamod.potion.strength", 25),
            createPotionItem(Items.POTION, Potions.HEALING, "merchant.arenamod.potion.healing", 25),
            createPotionItem(Items.POTION, Potions.REGENERATION, "merchant.arenamod.potion.regeneration", 25),
            createPotionItem(Items.POTION, Potions.FIRE_RESISTANCE, "merchant.arenamod.potion.fire_resistance", 25),
            createPotionItem(Items.POTION, Potions.WATER_BREATHING, "merchant.arenamod.potion.water_breathing", 25),
            createPotionItem(Items.POTION, Potions.NIGHT_VISION, "merchant.arenamod.potion.night_vision", 25),
            createPotionItem(Items.POTION, Potions.INVISIBILITY, "merchant.arenamod.potion.invisibility", 25),
            createPotionItem(Items.POTION, Potions.LEAPING, "merchant.arenamod.potion.leaping", 25),
            createPotionItem(Items.POTION, Potions.POISON, "merchant.arenamod.potion.poison", 25),
            createPotionItem(Items.POTION, Potions.SLOW_FALLING, "merchant.arenamod.potion.slow_falling", 25),
            createPotionItem(Items.POTION, Potions.TURTLE_MASTER, "merchant.arenamod.potion.turtle_master", 25)
    );

    /** 药水商人二级：喷溅药水 II级 (40 exp) */
    public static final List<MerchantItem> POTION_TIER_2 = List.of(
            createPotionItem(Items.SPLASH_POTION, Potions.STRONG_SWIFTNESS, "merchant.arenamod.potion.t2.swiftness", 40),
            createPotionItem(Items.SPLASH_POTION, Potions.STRONG_STRENGTH, "merchant.arenamod.potion.t2.strength", 40),
            createPotionItem(Items.SPLASH_POTION, Potions.STRONG_HEALING, "merchant.arenamod.potion.t2.healing", 40),
            createPotionItem(Items.SPLASH_POTION, Potions.STRONG_REGENERATION, "merchant.arenamod.potion.t2.regeneration", 40),
            createPotionItem(Items.SPLASH_POTION, Potions.STRONG_POISON, "merchant.arenamod.potion.t2.poison", 40),
            createPotionItem(Items.SPLASH_POTION, Potions.STRONG_LEAPING, "merchant.arenamod.potion.t2.leaping", 40),
            createPotionItem(Items.SPLASH_POTION, Potions.STRONG_TURTLE_MASTER, "merchant.arenamod.potion.t2.turtle_master", 40),
            createPotionItem(Items.SPLASH_POTION, Potions.SLOW_FALLING, "merchant.arenamod.potion.t2.slow_falling", 40),
            createPotionItem(Items.SPLASH_POTION, Potions.FIRE_RESISTANCE, "merchant.arenamod.potion.t2.fire_resistance", 40),
            createPotionItem(Items.SPLASH_POTION, Potions.WATER_BREATHING, "merchant.arenamod.potion.t2.water_breathing", 40),
            createPotionItem(Items.SPLASH_POTION, Potions.NIGHT_VISION, "merchant.arenamod.potion.t2.night_vision", 40),
            createPotionItem(Items.SPLASH_POTION, Potions.INVISIBILITY, "merchant.arenamod.potion.t2.invisibility", 40)
    );

    /** 药水商人三级：喷溅药水 X级 10分钟 (60 exp) */
    public static final List<MerchantItem> POTION_TIER_3 = List.of(
            createCustomSplashPotion(StatusEffects.SPEED, "merchant.arenamod.potion.t3.speed", 60),
            createCustomSplashPotion(StatusEffects.STRENGTH, "merchant.arenamod.potion.t3.strength", 60),
            createCustomSplashPotion(StatusEffects.INSTANT_HEALTH, "merchant.arenamod.potion.t3.healing", 60),
            createCustomSplashPotion(StatusEffects.REGENERATION, "merchant.arenamod.potion.t3.regeneration", 60),
            createCustomSplashPotion(StatusEffects.FIRE_RESISTANCE, "merchant.arenamod.potion.t3.fire_resistance", 60),
            createCustomSplashPotion(StatusEffects.WATER_BREATHING, "merchant.arenamod.potion.t3.water_breathing", 60),
            createCustomSplashPotion(StatusEffects.NIGHT_VISION, "merchant.arenamod.potion.t3.night_vision", 60),
            createCustomSplashPotion(StatusEffects.INVISIBILITY, "merchant.arenamod.potion.t3.invisibility", 60),
            createCustomSplashPotion(StatusEffects.JUMP_BOOST, "merchant.arenamod.potion.t3.leaping", 60),
            createCustomSplashPotion(StatusEffects.POISON, "merchant.arenamod.potion.t3.poison", 60),
            createCustomSplashPotion(StatusEffects.SLOW_FALLING, "merchant.arenamod.potion.t3.slow_falling", 60),
            createCustomSplashPotion(StatusEffects.DOLPHINS_GRACE, "merchant.arenamod.potion.t3.dolphins_grace", 60)
    );

    // ========== 统一查询接口 ==========

    /** 获取指定商人类型的商品列表 */
    public static List<MerchantItem> getItemsForMerchant(int merchantType, int tier) {
        return switch (merchantType) {
            case MERCHANT_TYPE_ITEM -> getItemsForTier(tier);
            case MERCHANT_TYPE_BLOCK -> getBlockItemsForTier(tier);
            case MERCHANT_TYPE_POTION -> getPotionItemsForTier(tier);
            default -> getItemsForTier(tier);
        };
    }

    /** 获取物品商人指定等级的商品 */
    public static List<MerchantItem> getItemsForTier(int tier) {
        return switch (tier) {
            case 1 -> TIER_1;
            case 2 -> TIER_2;
            case 3 -> TIER_3;
            default -> TIER_1;
        };
    }

    /** 获取方块商人指定等级的商品 */
    public static List<MerchantItem> getBlockItemsForTier(int tier) {
        return switch (tier) {
            case 1 -> BLOCK_TIER_1;
            case 2 -> BLOCK_TIER_2;
            case 3 -> BLOCK_TIER_3;
            default -> BLOCK_TIER_1;
        };
    }

    /** 获取药水商人指定等级的商品 */
    public static List<MerchantItem> getPotionItemsForTier(int tier) {
        return switch (tier) {
            case 1 -> POTION_TIER_1;
            case 2 -> POTION_TIER_2;
            case 3 -> POTION_TIER_3;
            default -> POTION_TIER_1;
        };
    }

    /** 获取商人名称（返回语言键） */
    public static String getMerchantName(int merchantType) {
        return switch (merchantType) {
            case MERCHANT_TYPE_ITEM -> "merchant.arenamod.merchant_name.item";
            case MERCHANT_TYPE_BLOCK -> "merchant.arenamod.merchant_name.block";
            case MERCHANT_TYPE_POTION -> "merchant.arenamod.merchant_name.potion";
            default -> "merchant.arenamod.merchant_name.item";
        };
    }

    /**
     * 获取从指定等级升级的费用（所有商人通用）
     */
    public static int getUpgradeCost(int currentTier) {
        var cfg = com.qidai.arenamod.config.ArenaConfig.getInstance();
        return switch (currentTier) {
            case 1 -> cfg.getMerchantUpgradeCostTier1();
            case 2 -> cfg.getMerchantUpgradeCostTier2();
            default -> Integer.MAX_VALUE;
        };
    }

    /** 获取分类显示名（返回语言键） */
    public static String getCategoryName(String category) {
        return switch (category) {
            case "weapon" -> "merchant.arenamod.category.weapon";
            case "food" -> "merchant.arenamod.category.food";
            case "armor" -> "merchant.arenamod.category.armor";
            case "tool" -> "merchant.arenamod.category.tool";
            case "pass" -> "merchant.arenamod.category.pass";
            case "other" -> "merchant.arenamod.category.other";
            case "block" -> "merchant.arenamod.category.block";
            case "potion" -> "merchant.arenamod.category.potion";
            default -> category;
        };
    }

    // ========== 辅助创建方法 ==========

    /**
     * 创建普通药水商品
     */
    private static MerchantItem createPotionItem(net.minecraft.item.Item item, net.minecraft.potion.Potion potion, String nameKey, int cost) {
        ItemStack stack = new ItemStack(item);
        PotionUtil.setPotion(stack, potion);
        stack.setCustomName(Text.translatable(nameKey).formatted(Formatting.AQUA));
        return new MerchantItem(stack, cost, "potion");
    }

    /**
     * 创建自定义喷溅药水（三级：X级，10分钟）
     */
    private static MerchantItem createCustomSplashPotion(net.minecraft.entity.effect.StatusEffect effect, String nameKey, int cost) {
        ItemStack stack = new ItemStack(Items.SPLASH_POTION);
        NbtCompound nbt = stack.getOrCreateNbt();
        NbtList effects = new NbtList();

        int duration = 12000; // 10分钟 = 12000 ticks
        int amplifier = 9; // X级 = amplifier 9

        // 使用StatusEffectInstance自带的序列化，确保格式正确
        StatusEffectInstance instance;
        if (effect == StatusEffects.INSTANT_HEALTH || effect == StatusEffects.INSTANT_DAMAGE) {
            instance = new StatusEffectInstance(effect, 1, amplifier);
        } else {
            instance = new StatusEffectInstance(effect, duration, amplifier);
        }

        NbtCompound effectData = new NbtCompound();
        instance.writeNbt(effectData);
        effects.add(effectData);

        nbt.put("CustomPotionEffects", effects);
        nbt.putString("Potion", "minecraft:water"); // 默认颜色
        stack.setCustomName(Text.translatable(nameKey).formatted(Formatting.LIGHT_PURPLE, Formatting.BOLD));
        return new MerchantItem(stack, cost, "potion");
    }

    /**
     * 创建超级剑：钻石剑材质，1000伤害，150耐久
     */
    private static ItemStack createSuperSword() {
        ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);

        stack.addAttributeModifier(
                EntityAttributes.GENERIC_ATTACK_DAMAGE,
                new EntityAttributeModifier(
                        UUID.fromString("cb3f55d3-645c-4f38-a497-9c13a33db5cf"),
                        "generic.attack_damage",
                        993.0,
                        EntityAttributeModifier.Operation.ADDITION
                ),
                EquipmentSlot.MAINHAND
        );

        stack.setDamage(stack.getMaxDamage() - 150);
        stack.setCustomName(Text.translatable("merchant.arenamod.super_sword").formatted(Formatting.GOLD, Formatting.BOLD));

        return stack;
    }

    /**
     * 创建药水箭
     */
    private static ItemStack createTippedArrow(net.minecraft.potion.Potion potion, int count) {
        ItemStack stack = new ItemStack(Items.TIPPED_ARROW, count);
        PotionUtil.setPotion(stack, potion);
        return stack;
    }

    /**
     * 创建一个通行证（允许携带一个物品离开竞技场）
     */
    public static ItemStack createPass() {
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.setCustomName(Text.translatable("merchant.arenamod.pass").formatted(Formatting.GREEN, Formatting.BOLD));
        stack.getOrCreateNbt().putBoolean("ArenaPass", true);
        return stack;
    }

    /**
     * 判断物品是否为通行证（检查物品类型和 NBT 双重验证）
     */
    public static boolean isPassItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.getItem() != Items.PAPER) return false;
        return stack.getNbt() != null && stack.getNbt().contains("ArenaPass");
    }
}
