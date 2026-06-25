package com.qidai.arenamod.block;

import com.qidai.arenamod.ArenaMod;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class ModBlocks {
    public static final Block ARENA_CORE = register("arena_core",
            new ArenaCoreBlock(FabricBlockSettings.copyOf(Blocks.BEACON)
                    .nonOpaque()
                    .requiresTool()
                    .strength(-1.0f, 3600000.0f))); // 不可破坏

    private static Block register(String name, Block block) {
        Identifier id = new Identifier(ArenaMod.MOD_ID, name);
        Registry.register(Registries.BLOCK, id, block);
        Registry.register(Registries.ITEM, id, new BlockItem(block, new FabricItemSettings()));
        return block;
    }

    public static void register() {
        ArenaMod.LOGGER.info("注册方块");
    }
}
