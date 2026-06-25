package com.qidai.arenamod.screen;

import com.qidai.arenamod.network.ModClientNetworking;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * 模式选择界面 - 玩家选择单人模式或PVP模式
 */
public class ModeSelectScreen extends Screen {
    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 40;
    private static final int BUTTON_SPACING = 10;
    private boolean modeSent = false;

    public ModeSelectScreen() {
        super(Text.translatable("screen.arenamod.mode_select"));
    }

    @Override
    protected void init() {
        super.init();

        int centerX = width / 2;
        int startY = height / 2 - BUTTON_HEIGHT - BUTTON_SPACING / 2;

        // 单人模式按钮
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.arenamod.mode_select.single"),
                button -> sendMode(0)
        ).dimensions(centerX - BUTTON_WIDTH / 2, startY, BUTTON_WIDTH, BUTTON_HEIGHT).build());

        // PVP模式按钮
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.arenamod.mode_select.pvp"),
                button -> sendMode(1)
        ).dimensions(centerX - BUTTON_WIDTH / 2, startY + BUTTON_HEIGHT + BUTTON_SPACING, BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    private void sendMode(int mode) {
        if (modeSent) return;
        modeSent = true;
        ModClientNetworking.sendModeSelection(mode);
        close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}
