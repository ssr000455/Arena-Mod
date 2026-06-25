package com.qidai.arenamod.item;

import com.qidai.arenamod.ArenaMod;
import com.qidai.arenamod.block.ModBlocks;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class ModItemGroup {
    public static final ItemGroup ARENA_GROUP = FabricItemGroup.builder()
            .icon(() -> new ItemStack(ModItems.EXP_POINT))
            .displayName(Text.translatable("itemGroup.arenamod.arena_core"))
            .entries((context, entries) -> {
                entries.add(ModBlocks.ARENA_CORE);
                entries.add(ModItems.EXP_POINT);
            })
            .build();

    public static void register() {
        Registry.register(Registries.ITEM_GROUP,
                new Identifier(ArenaMod.MOD_ID, "arena_group"),
                ARENA_GROUP);
        ArenaMod.LOGGER.info("注册创造模式标签页");
    }
}
