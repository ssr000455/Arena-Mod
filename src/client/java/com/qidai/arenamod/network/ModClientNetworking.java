package com.qidai.arenamod.network;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;

/**
 * 客户端网络处理
 */
public class ModClientNetworking {

    /** 客户端缓存的游戏进度数据（用于HUD渲染） */
    public static volatile GameProgressData currentProgress = null;

    public static void register() {
        // 注册接收服务端打开模式选择界面的包
        ClientPlayNetworking.registerGlobalReceiver(ModNetworking.OPEN_MODE_SELECT_ID, (client, handler, buf, responseSender) -> {
            client.execute(() -> {
                client.setScreen(new com.qidai.arenamod.screen.ModeSelectScreen());
            });
        });

        // 注册接收服务端打开商人界面的包
        ClientPlayNetworking.registerGlobalReceiver(ModNetworking.OPEN_MERCHANT_ID, (client, handler, buf, responseSender) -> {
            int tier = buf.readInt();
            boolean upgradeAvailable = buf.readBoolean();
            int merchantType = buf.readInt();
            client.execute(() -> {
                client.setScreen(new com.qidai.arenamod.screen.MerchantScreen(tier, upgradeAvailable, merchantType));
            });
        });

        // 注册接收游戏进度同步的包（用于HUD进度条和PVP倒计时）
        ClientPlayNetworking.registerGlobalReceiver(ModNetworking.GAME_PROGRESS_ID, (client, handler, buf, responseSender) -> {
            int gameMode = buf.readInt();       // 0=单人, 1=PVP
            int currentWave = buf.readInt();
            int currentBatch = buf.readInt();
            boolean hasWave = buf.readBoolean();
            final int totalBatches;
            if (hasWave) {
                totalBatches = buf.readInt();
            } else {
                totalBatches = 0;
            }
            int stateOrdinal = buf.readInt();
            int expPoints = buf.readInt();
            int lives = buf.readInt();

            // PVP 额外数据
            final int pvpRemainingTicks;
            final int pvpAliveCount;
            final int pvpTotalPlayerCount;
            final int pvpTotalTimeTicks;
            if (gameMode == 1) {
                pvpRemainingTicks = buf.readInt();
                pvpAliveCount = buf.readInt();
                pvpTotalPlayerCount = buf.readInt();
                pvpTotalTimeTicks = buf.readInt();
            } else {
                pvpRemainingTicks = 0;
                pvpAliveCount = 0;
                pvpTotalPlayerCount = 0;
                pvpTotalTimeTicks = 0;
            }

            client.execute(() -> {
                currentProgress = new GameProgressData(
                        gameMode, currentWave, currentBatch, totalBatches,
                        stateOrdinal, expPoints, lives,
                        pvpRemainingTicks, pvpAliveCount, pvpTotalPlayerCount,
                        pvpTotalTimeTicks
                );
            });
        });
    }

    /**
     * 客户端缓存的游戏进度数据
     */
    public record GameProgressData(
            int gameMode,       // 0=单人, 1=PVP
            int currentWave,
            int currentBatch,
            int totalBatches,
            int stateOrdinal,
            int expPoints,
            int lives,
            // PVP 字段
            int pvpRemainingTicks,
            int pvpAliveCount,
            int pvpTotalPlayerCount,
            int pvpTotalTimeTicks    // 服务端配置的总时限
    ) {
        public float getProgress() {
            if (totalBatches <= 0) return 0f;
            return (float) currentBatch / totalBatches;
        }

        public boolean isPVP() { return gameMode == 1; }

        /** PVP时间进度 0.0~1.0（使用服务端传来的总时限） */
        public float getPvpTimeProgress() {
            if (pvpTotalTimeTicks <= 0) return 0f;
            return 1f - (float) pvpRemainingTicks / pvpTotalTimeTicks;
        }

        /** PVP剩余秒数 */
        public int getPvpRemainingSeconds() {
            return pvpRemainingTicks / 20;
        }

        /** PVP存活玩家文本 */
        public String getPvpAliveText() {
            return pvpAliveCount + "/" + pvpTotalPlayerCount;
        }
    }

    /**
     * 发送模式选择到服务端
     */
    public static void sendModeSelection(int modeOrdinal) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(modeOrdinal);
        ClientPlayNetworking.send(ModNetworking.SELECT_MODE_ID, buf);
    }

    /**
     * 发送购买请求到服务端
     */
    public static void sendMerchantBuy(int itemIndex, int merchantType) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(itemIndex);
        buf.writeInt(merchantType);
        ClientPlayNetworking.send(ModNetworking.MERCHANT_BUY_ID, buf);
    }
}
