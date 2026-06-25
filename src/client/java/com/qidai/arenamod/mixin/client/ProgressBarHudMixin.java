package com.qidai.arenamod.mixin.client;

import com.qidai.arenamod.client.ArenaMusicManager;
import com.qidai.arenamod.dimension.ModDimensions;
import com.qidai.arenamod.network.ModClientNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在屏幕顶部渲染竞技场进度条 + 驱动音乐播放
 */
@Mixin(InGameHud.class)
public class ProgressBarHudMixin {

    @Inject(at = @At("RETURN"), method = "render")
    private void onRenderHud(DrawContext context, float tickDelta, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return;

        boolean inArena = client.world.getRegistryKey().equals(ModDimensions.ARENA_WORLD_KEY);

        // 驱动音乐播放（检测维度变化控制启停）
        ArenaMusicManager.tick(inArena);

        if (!inArena) return;

        var progress = ModClientNetworking.currentProgress;
        if (progress == null) return;

        if (progress.isPVP()) {
            renderPvpHud(context, client, progress);
        } else {
            renderSinglePlayerHud(context, client, progress);
        }
    }

    private void renderSinglePlayerHud(DrawContext context, MinecraftClient client,
                                        ModClientNetworking.GameProgressData progress) {
        int screenWidth = client.getWindow().getScaledWidth();
        int barWidth = 200;
        int barHeight = 8;
        int barX = (screenWidth - barWidth) / 2;
        int barY = 4;

        // 背景
        context.fill(barX - 1, barY - 1, barX + barWidth + 1, barY + barHeight + 1, 0xFF000000);
        context.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF333333);

        // 进度条
        int fillWidth = (int) (barWidth * progress.getProgress());
        if (fillWidth > 0) {
            int color = 0xFF00AA00;
            if (progress.currentBatch() >= progress.totalBatches() - 1) {
                color = 0xFFFFAA00;
            }
            context.fill(barX, barY, barX + fillWidth, barY + barHeight, color);
        }

        int textY = barY + barHeight + 2;

        // 波次信息
        context.drawText(client.textRenderer,
                Text.translatable("hud.arenamod.wave").append(Text.literal(": " + (progress.currentWave() + 1)))
                        .formatted(Formatting.AQUA, Formatting.BOLD),
                barX, textY, 0xFFFFFFFF, true);

        // 批次信息
        context.drawText(client.textRenderer,
                Text.translatable("hud.arenamod.batch").append(Text.literal(": " + (progress.currentBatch() + 1) + "/" + progress.totalBatches()))
                        .formatted(Formatting.WHITE),
                barX + 80, textY, 0xFFFFFFFF, true);

        // 生命值
        if (progress.lives() > 0) {
            Formatting livesColor = progress.lives() <= 5 ? Formatting.RED : Formatting.GREEN;
            context.drawText(client.textRenderer,
                    Text.translatable("hud.arenamod.lives").append(Text.literal(": " + progress.lives())).formatted(livesColor, Formatting.BOLD),
                    barX + 150, textY, 0xFFFFFFFF, true);
        }

        // EXP点数（右侧）
        String expStr = String.valueOf(progress.expPoints());
        Text expText = Text.translatable("hud.arenamod.exp").append(Text.literal(": " + expStr)).formatted(Formatting.YELLOW);
        context.drawText(client.textRenderer, expText,
                screenWidth - barX - client.textRenderer.getWidth(expText), textY, 0xFFFFFFFF, true);
    }

    private void renderPvpHud(DrawContext context, MinecraftClient client,
                               ModClientNetworking.GameProgressData progress) {
        int screenWidth = client.getWindow().getScaledWidth();
        int barWidth = 200;
        int barHeight = 10;
        int barX = (screenWidth - barWidth) / 2;
        int barY = 4;

        // 进度条背景
        context.fill(barX - 1, barY - 1, barX + barWidth + 1, barY + barHeight + 1, 0xFF000000);
        context.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF333333);

        // 时间进度条（从满到空）
        float timeProgress = progress.getPvpTimeProgress();
        int fillWidth = (int) (barWidth * (1f - timeProgress));
        if (fillWidth > 0) {
            int color;
            if (timeProgress < 0.5f) {
                color = 0xFF00CC00;       // 绿色
            } else if (timeProgress < 0.75f) {
                color = 0xFFCCCC00;       // 黄色
            } else {
                color = 0xFFCC0000;       // 红色
            }
            context.fill(barX, barY, barX + fillWidth, barY + barHeight, color);
        }

        int textY = barY + barHeight + 2;

        // 倒计时文字
        int remainingSec = progress.getPvpRemainingSeconds();
        String timeStr = String.format("%02d:%02d", remainingSec / 60, remainingSec % 60);
        Formatting timeColor = remainingSec > 120 ? Formatting.GREEN :
                (remainingSec > 60 ? Formatting.YELLOW : Formatting.RED);
        context.drawText(client.textRenderer,
                Text.translatable("hud.arenamod.time").append(Text.literal(": " + timeStr)).formatted(timeColor, Formatting.BOLD),
                barX, textY, 0xFFFFFFFF, true);

        // 存活人数（中间）
        String aliveStr = progress.pvpAliveCount() + "/" + progress.pvpTotalPlayerCount();
        Text aliveText = Text.translatable("hud.arenamod.alive").append(Text.literal(": " + aliveStr)).formatted(Formatting.WHITE, Formatting.BOLD);
        int aliveWidth = client.textRenderer.getWidth(aliveText);
        context.drawText(client.textRenderer, aliveText,
                (screenWidth - aliveWidth) / 2, textY, 0xFFFFFFFF, true);

        // EXP（右侧）
        String expVal = String.valueOf(progress.expPoints());
        Text expLabel = Text.translatable("hud.arenamod.exp").append(Text.literal(": " + expVal)).formatted(Formatting.YELLOW);
        int expWidth = client.textRenderer.getWidth(expLabel);
        context.drawText(client.textRenderer, expLabel,
                screenWidth - barX - expWidth, textY, 0xFFFFFFFF, true);

        // 第二行：奖杯数
        int textY2 = textY + 12;
        context.drawText(client.textRenderer,
                Text.translatable("hud.arenamod.trophies").append(Text.literal(": " + progress.lives())).formatted(Formatting.GOLD),
                barX, textY2, 0xFFFFFFFF, true);
    }
}
