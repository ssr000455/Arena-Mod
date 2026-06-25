package com.qidai.arenamod.network;

import com.qidai.arenamod.ArenaMod;
import com.qidai.arenamod.game.GameManager;
import com.qidai.arenamod.game.GameMode;
import com.qidai.arenamod.game.GameSaveManager;
import com.qidai.arenamod.game.GameInstance;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * 网络通信管理
 */
public class ModNetworking {
    public static final String MOD_ID = ArenaMod.MOD_ID;

    // S2C: 打开模式选择界面
    public static final Identifier OPEN_MODE_SELECT_ID = new Identifier(MOD_ID, "open_mode_select");
    // C2S: 选择模式
    public static final Identifier SELECT_MODE_ID = new Identifier(MOD_ID, "select_mode");
    // C2S: 保存退出
    public static final Identifier SAVE_EXIT_ID = new Identifier(MOD_ID, "save_exit");
    // C2S: 保存退出并断开连接（服务端处理后断开玩家）
    public static final Identifier SAVE_EXIT_DISCONNECT_ID = new Identifier(MOD_ID, "save_exit_disconnect");
    // C2S: 继续存档
    public static final Identifier RESUME_SAVE_ID = new Identifier(MOD_ID, "resume_save");
    // C2S: 新建游戏（忽略存档）
    public static final Identifier NEW_GAME_ID = new Identifier(MOD_ID, "new_game");

    // S2C: 打开商人界面
    public static final Identifier OPEN_MERCHANT_ID = new Identifier(MOD_ID, "open_merchant");
    // C2S: 购买商品
    public static final Identifier MERCHANT_BUY_ID = new Identifier(MOD_ID, "merchant_buy");

    // S2C: 游戏进度同步（用于HUD进度条）
    public static final Identifier GAME_PROGRESS_ID = new Identifier(MOD_ID, "game_progress");

    /**
     * 注册服务端接收的包
     */
    public static void registerC2SPackets() {
        // 接收玩家选择的模式
        ServerPlayNetworking.registerGlobalReceiver(SELECT_MODE_ID, (server, player, handler, buf, responseSender) -> {
            int modeOrdinal = buf.readInt();
            GameMode mode = GameMode.values()[modeOrdinal];

            server.execute(() -> {
                ArenaMod.LOGGER.info("玩家 {} 选择了模式: {}", player.getName().getString(), mode);

                // 检查是否有存档
                boolean hasSave = GameSaveManager.getInstance().hasSave(server, player.getUuid());
                if (hasSave) {
                    // 通知客户端询问是否继续存档
                    sendResumePrompt(player);
                    return;
                }

                startGameForPlayer(player, mode);
            });
        });

        // 保存退出
        ServerPlayNetworking.registerGlobalReceiver(SAVE_EXIT_ID, (server, player, handler, buf, responseSender) -> {
            server.execute(() -> {
                GameInstance game = GameManager.getInstance().getGame(player);
                if (game != null) {
                    GameSaveManager.getInstance().saveGame(server, game);
                    GameManager.getInstance().endGame(player, game);
                    player.sendMessage(net.minecraft.text.Text.translatable("message.arenamod.game_saved"));
                }
            });
        });

        // 保存退出并断开连接
        ServerPlayNetworking.registerGlobalReceiver(SAVE_EXIT_DISCONNECT_ID, (server, player, handler, buf, responseSender) -> {
            server.execute(() -> {
                GameInstance game = GameManager.getInstance().getGame(player);
                if (game != null) {
                    GameSaveManager.getInstance().saveGame(server, game);
                    GameManager.getInstance().endGame(player, game);
                }
                // 断开玩家连接
                handler.disconnect(net.minecraft.text.Text.translatable("message.arenamod.game_saved_disconnect"));
            });
        });

        // 继续存档
        ServerPlayNetworking.registerGlobalReceiver(RESUME_SAVE_ID, (server, player, handler, buf, responseSender) -> {
            server.execute(() -> {
                GameSaveManager.GameSaveData saveData = GameSaveManager.getInstance().loadGame(server, player.getUuid());
                if (saveData != null) {
                    GameManager.getInstance().resumeGame(player, saveData);
                }
            });
        });

        // 新建游戏（忽略存档）
        ServerPlayNetworking.registerGlobalReceiver(NEW_GAME_ID, (server, player, handler, buf, responseSender) -> {
            server.execute(() -> {
                GameSaveManager.getInstance().deleteSave(server, player.getUuid());
                int modeOrdinal = buf.readInt();
                GameMode mode = GameMode.values()[modeOrdinal];
                startGameForPlayer(player, mode);
            });
        });

        // 购买商品
        ServerPlayNetworking.registerGlobalReceiver(MERCHANT_BUY_ID, (server, player, handler, buf, responseSender) -> {
            int itemIndex = buf.readInt();
            int merchantType = buf.readInt();
            server.execute(() -> {
                GameManager.getInstance().handleMerchantBuy(player, itemIndex, merchantType);
            });
        });
    }

    private static void startGameForPlayer(ServerPlayerEntity player, GameMode mode) {
        switch (mode) {
            case SINGLE_PLAYER -> GameManager.getInstance().startSinglePlayerGame(player);
            case PVP -> GameManager.getInstance().joinPVPQueue(player);
        }
    }

    /**
     * 向客户端发送继续提示
     */
    private static void sendResumePrompt(ServerPlayerEntity player) {
        // 简单起见，直接发送文本提示，客户端用聊天方式响应
        player.sendMessage(net.minecraft.text.Text.translatable("message.arenamod.save_found"));
        player.sendMessage(net.minecraft.text.Text.translatable("message.arenamod.save_resume_hint"));
        player.sendMessage(net.minecraft.text.Text.translatable("message.arenamod.save_new_hint"));
    }

    /**
     * 服务端发送打开模式选择界面的包
     */
    public static PacketByteBuf createOpenModeSelectPacket() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(0);
        return buf;
    }
}
