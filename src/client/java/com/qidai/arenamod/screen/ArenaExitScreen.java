package com.qidai.arenamod.screen;

import com.qidai.arenamod.network.ModNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * 竞技场退出确认界面 - 保存并退出 / 保存并断开 / 继续游戏
 */
public class ArenaExitScreen extends Screen {
    private static final int BUTTON_WIDTH = 220;
    private static final int BUTTON_HEIGHT = 40;

    public ArenaExitScreen() {
        super(Text.translatable("screen.arenamod.exit_title"));
    }

    @Override
    protected void init() {
        super.init();

        int centerX = width / 2;
        int startY = height / 2 - BUTTON_HEIGHT - 15;

        // 保存并退出（回到主世界继续玩）
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.arenamod.exit_save"),
                button -> {
                    ClientPlayNetworking.send(ModNetworking.SAVE_EXIT_ID, PacketByteBufs.create());
                    close();
                }
        ).dimensions(centerX - BUTTON_WIDTH / 2, startY, BUTTON_WIDTH, BUTTON_HEIGHT).build());

        // 保存并返回主菜单（断开连接）
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.arenamod.exit_disconnect"),
                button -> {
                    ClientPlayNetworking.send(ModNetworking.SAVE_EXIT_DISCONNECT_ID, PacketByteBufs.create());
                    MinecraftClient client = MinecraftClient.getInstance();
                    if (client.getNetworkHandler() != null) {
                        client.getNetworkHandler().getConnection().disconnect(
                                Text.translatable("screen.arenamod.exit_disconnected"));
                    }
                }
        ).dimensions(centerX - BUTTON_WIDTH / 2, startY + BUTTON_HEIGHT + 5, BUTTON_WIDTH, BUTTON_HEIGHT).build());

        // 继续游戏
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.arenamod.exit_continue"),
                button -> close()
        ).dimensions(centerX - BUTTON_WIDTH / 2, startY + (BUTTON_HEIGHT + 5) * 2, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    @Override
    public boolean shouldPause() {
        return true;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}
