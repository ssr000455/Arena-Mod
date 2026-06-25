package com.qidai.arenamod.mixin.client;

import com.qidai.arenamod.dimension.ModDimensions;
import com.qidai.arenamod.screen.ArenaExitScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 当玩家在竞技场维度时，将暂停菜单替换为退出确认界面
 */
@Mixin(MinecraftClient.class)
public class OpenPauseMenuMixin {

    @Inject(at = @At("HEAD"), method = "openPauseMenu", cancellable = true)
    private void onOpenPauseMenu(CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null && client.world != null) {
            if (client.world.getRegistryKey().equals(ModDimensions.ARENA_WORLD_KEY)) {
                client.setScreen(new ArenaExitScreen());
                ci.cancel();
            }
        }
    }
}
