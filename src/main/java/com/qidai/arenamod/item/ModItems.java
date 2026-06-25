package com.qidai.arenamod.item;

import com.qidai.arenamod.ArenaMod;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;

public class ModItems {
    public static final Item EXP_POINT = register("exp_point",
            new Item(new FabricItemSettings().maxCount(9999).rarity(Rarity.UNCOMMON)));

    private static Item register(String name, Item item) {
        Identifier id = new Identifier(ArenaMod.MOD_ID, name);
        return Registry.register(Registries.ITEM, id, item);
    }

    public static void register() {
        ArenaMod.LOGGER.info("注册物品");
    }
}
