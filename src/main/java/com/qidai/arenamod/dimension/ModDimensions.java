package com.qidai.arenamod.dimension;

import com.qidai.arenamod.ArenaMod;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

public class ModDimensions {
    public static final RegistryKey<World> ARENA_WORLD_KEY = RegistryKey.of(
            RegistryKeys.WORLD,
            new Identifier(ArenaMod.MOD_ID, "arena")
    );

    public static void register() {
        ArenaMod.LOGGER.info("注册维度: {}", ARENA_WORLD_KEY.getValue());
    }
}
