package com.qidai.arenamod.game;

import com.qidai.arenamod.ArenaMod;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 游戏存档管理器 - 保存/读取玩家游戏进度（含经验点）
 */
public class GameSaveManager {
    private static GameSaveManager INSTANCE;

    private GameSaveManager() {}

    public static GameSaveManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new GameSaveManager();
        }
        return INSTANCE;
    }

    private Path getSaveDir(MinecraftServer server) {
        Path worldDir = server.getSavePath(WorldSavePath.ROOT);
        Path saveDir = worldDir.resolve("arenamod").resolve("saves");
        try {
            Files.createDirectories(saveDir);
        } catch (IOException e) {
            ArenaMod.LOGGER.error("无法创建存档目录", e);
        }
        return saveDir;
    }

    private Path getSaveFile(MinecraftServer server, UUID playerUuid) {
        return getSaveDir(server).resolve(playerUuid.toString() + ".dat");
    }

    /**
     * 保存游戏进度
     */
    public void saveGame(MinecraftServer server, GameInstance game) {
        Path saveFile = getSaveFile(server, game.getPlayerUuid());

        NbtCompound nbt = new NbtCompound();
        nbt.putInt("waveIndex", game.getCurrentWaveIndex());
        nbt.putInt("batchIndex", game.getCurrentBatchIndex());
        nbt.putInt("arenaCenterX", game.getArenaCenter().getX());
        nbt.putInt("arenaCenterY", game.getArenaCenter().getY());
        nbt.putInt("arenaCenterZ", game.getArenaCenter().getZ());
        nbt.putString("gameMode", game.getGameMode().name());
        nbt.putLong("elapsedTime", game.getElapsedTime());
        nbt.putInt("expPoints", game.getTotalExpPoints());
        nbt.putInt("trophyCount", game.getTrophyCount());
        nbt.putInt("passCount", game.getPassCount());
        nbt.putInt("blockMerchantTier", game.getBlockMerchantTier());
        nbt.putInt("potionMerchantTier", game.getPotionMerchantTier());
        nbt.putInt("lives", game.getLives());
        nbt.putInt("cycleCount", game.getCycleCount());

        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(saveFile.toFile()))) {
            NbtIo.write(nbt, dos);
            ArenaMod.LOGGER.info("游戏进度已保存: {}", saveFile);
        } catch (IOException e) {
            ArenaMod.LOGGER.error("保存游戏进度失败", e);
        }
    }

    /**
     * 读取游戏进度
     */
    public GameSaveData loadGame(MinecraftServer server, UUID playerUuid) {
        Path saveFile = getSaveFile(server, playerUuid);
        if (!Files.exists(saveFile)) {
            return null;
        }

        try (DataInputStream dis = new DataInputStream(new FileInputStream(saveFile.toFile()))) {
            NbtCompound nbt = NbtIo.read(dis);
            if (nbt == null) return null;

            int waveIndex = nbt.getInt("waveIndex");
            int batchIndex = nbt.getInt("batchIndex");
            int centerX = nbt.getInt("arenaCenterX");
            int centerY = nbt.getInt("arenaCenterY");
            int centerZ = nbt.getInt("arenaCenterZ");
            String mode = nbt.getString("gameMode");
            long elapsed = nbt.getLong("elapsedTime");
            int expPoints = nbt.getInt("expPoints");
            int trophyCount = nbt.getInt("trophyCount");
            int passCount = nbt.getInt("passCount");
            int blockMerchantTier = nbt.getInt("blockMerchantTier");
            int potionMerchantTier = nbt.getInt("potionMerchantTier");
            int lives = nbt.getInt("lives");
            int cycleCount = nbt.getInt("cycleCount");

            return new GameSaveData(waveIndex, batchIndex,
                    new net.minecraft.util.math.BlockPos(centerX, centerY, centerZ),
                    GameMode.valueOf(mode), elapsed, expPoints, trophyCount, passCount, blockMerchantTier, potionMerchantTier, lives, cycleCount);
        } catch (IOException e) {
            ArenaMod.LOGGER.error("读取游戏进度失败", e);
            return null;
        }
    }

    public void deleteSave(MinecraftServer server, UUID playerUuid) {
        Path saveFile = getSaveFile(server, playerUuid);
        try {
            Files.deleteIfExists(saveFile);
        } catch (IOException e) {
            ArenaMod.LOGGER.error("删除存档失败", e);
        }
    }

    public boolean hasSave(MinecraftServer server, UUID playerUuid) {
        return Files.exists(getSaveFile(server, playerUuid));
    }

    // ===== 奖杯持久化（独立于游戏会话） =====

    private Path getTrophyDir(MinecraftServer server) {
        Path worldDir = server.getSavePath(WorldSavePath.ROOT);
        Path trophyDir = worldDir.resolve("arenamod").resolve("trophies");
        try {
            Files.createDirectories(trophyDir);
        } catch (IOException e) {
            ArenaMod.LOGGER.error("无法创建奖杯目录", e);
        }
        return trophyDir;
    }

    private Path getTrophyFile(MinecraftServer server, UUID playerUuid) {
        return getTrophyDir(server).resolve(playerUuid.toString() + ".dat");
    }

    /**
     * 读取玩家奖杯数
     */
    public int loadTrophyCount(MinecraftServer server, UUID playerUuid) {
        Path file = getTrophyFile(server, playerUuid);
        if (!Files.exists(file)) return 0;

        try (DataInputStream dis = new DataInputStream(new FileInputStream(file.toFile()))) {
            NbtCompound nbt = NbtIo.read(dis);
            if (nbt == null) return 0;
            return nbt.getInt("trophyCount");
        } catch (IOException e) {
            ArenaMod.LOGGER.error("读取奖杯数据失败", e);
            return 0;
        }
    }

    /**
     * 保存玩家奖杯数
     */
    public void saveTrophyCount(MinecraftServer server, UUID playerUuid, int count) {
        Path file = getTrophyFile(server, playerUuid);

        NbtCompound nbt = new NbtCompound();
        nbt.putInt("trophyCount", count);

        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(file.toFile()))) {
            NbtIo.write(nbt, dos);
        } catch (IOException e) {
            ArenaMod.LOGGER.error("保存奖杯数据失败", e);
        }
    }

    /**
     * 增加玩家奖杯数并保存
     */
    public void addTrophyCount(MinecraftServer server, UUID playerUuid, int amount) {
        int current = loadTrophyCount(server, playerUuid);
        saveTrophyCount(server, playerUuid, current + amount);
    }

    public record GameSaveData(
            int waveIndex,
            int batchIndex,
            net.minecraft.util.math.BlockPos arenaCenter,
            GameMode gameMode,
            long elapsedTime,
            int expPoints,
            int trophyCount,
            int passCount,
            int blockMerchantTier,
            int potionMerchantTier,
            int lives,
            int cycleCount
    ) {}
}
