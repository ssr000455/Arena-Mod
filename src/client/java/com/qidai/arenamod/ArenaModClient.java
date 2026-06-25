package com.qidai.arenamod;

import com.qidai.arenamod.client.ArenaMusicCommands;
import com.qidai.arenamod.client.ArenaMusicManager;
import net.fabricmc.api.ClientModInitializer;

public class ArenaModClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // 注册客户端网络包（S2C）
        com.qidai.arenamod.network.ModClientNetworking.register();

        // 初始化音乐管理器（扫描音乐文件）
        ArenaMusicManager.init();
        ArenaMusicCommands.register();

        ArenaMod.LOGGER.info("Arena Mod 客户端初始化完成！");
    }
}
