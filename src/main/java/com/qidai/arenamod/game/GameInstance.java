package com.qidai.arenamod.game;

import com.qidai.arenamod.ArenaMod;
import com.qidai.arenamod.config.ArenaConfig;
import com.qidai.arenamod.wave.WaveDefinition;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GameInstance {
    private final UUID playerUuid;
    private final GameMode gameMode;
    private GameState state = GameState.WAITING;
    private final BlockPos arenaCenter;
    private final long startTime;

    // 波次进度
    private int currentWaveIndex = 0;
    private int currentBatchIndex = 0;
    private List<WaveDefinition.Wave> waves;
    private final List<MobEntity> currentBatchMobs = new ArrayList<>();

    // PVP相关
    private final List<UUID> pvpPlayers = new ArrayList<>();
    // PVP观众列表（已淘汰但还在游戏中等待结束）
    private final List<UUID> pvpSpectators = new ArrayList<>();
    // PVP 玩家独立背包存档（每人一份，不共享）
    private final Map<UUID, NbtCompound> savedInventories = new HashMap<>();
    private final Map<UUID, NbtCompound> savedEffectsMap = new HashMap<>();
    // PVP 时限（从配置读取秒数转毫秒）
    public static int getPvpTimeLimitMillis() {
        return ArenaConfig.getInstance().getGameTime() * 1000;
    }
    private long pvpStartTimeMs = 0;
    private int pvpTotalPlayerCount = 0;

    // 杀敌数/经验点统计
    private int killCount = 0;
    private int totalExpPoints = 0;

    // 保存玩家进入竞技场前的背包和效果
    private NbtCompound savedInventory = null;
    private NbtCompound savedEffects = null;

    // ===== PVP 追踪字段 =====
    private int trophyCount = 0;                          // 奖杯数
    private final Set<BlockPos> placedBlocks = ConcurrentHashMap.newKeySet(); // 玩家放置的方块
    private int arenaHalf;                                 // 竞技场半边长（根据模式确定）
    private int arenaHeight;                               // 竞技场限高

    // 地板循环追踪
    private long lastFloorToggleTime = 0;
    private long floorRestoreScheduledTime = 0;
    private boolean floorRemoved = false;

    // 方块清除追踪
    private long lastBlockClearTime = 0;

    // 越界检测追踪
    private long lastBoundaryCheckTime = 0;

    // ===== 商人字段 =====
    private UUID merchantUuid = null;
    private int merchantTier = 1;
    private boolean upgradePurchased = false;
    private int passCount = 0;

    // ===== 方块商人字段 =====
    private UUID blockMerchantUuid = null;
    private int blockMerchantTier = 1;
    private boolean blockMerchantUpgradePurchased = false;

    // ===== 药水商人字段 =====
    private UUID potionMerchantUuid = null;
    private int potionMerchantTier = 1;
    private boolean potionMerchantUpgradePurchased = false;

    // ===== 生命值/轮回系统 =====
    private int lives;                 // 单人模式初始命数（从配置读取）
    private int cycleCount = 0;       // 已完成的轮回数

    public GameInstance(ServerPlayerEntity player, GameMode mode, BlockPos arenaCenter) {
        this.playerUuid = player.getUuid();
        this.gameMode = mode;
        this.arenaCenter = arenaCenter;
        this.startTime = System.currentTimeMillis();
        this.waves = WaveDefinition.getWaves();
        this.lives = ArenaConfig.getInstance().getInitialLives();

        if (mode == GameMode.PVP) {
            pvpPlayers.add(player.getUuid());
            this.arenaHalf = ArenaBuilder.getPvpArenaHalf();
            this.arenaHeight = ArenaBuilder.getPvpArenaHeight();
        } else {
            this.arenaHalf = ArenaBuilder.getArenaHalf();
            this.arenaHeight = 100; // 单人模式固定100
        }
    }

    public UUID getPlayerUuid() { return playerUuid; }
    public GameMode getGameMode() { return gameMode; }
    public GameState getState() { return state; }
    public void setState(GameState state) { this.state = state; }
    public BlockPos getArenaCenter() { return arenaCenter; }
    public int getCurrentWaveIndex() { return currentWaveIndex; }
    public int getCurrentBatchIndex() { return currentBatchIndex; }
    public List<MobEntity> getCurrentBatchMobs() { return currentBatchMobs; }
    public List<UUID> getPvpPlayers() { return pvpPlayers; }

    public int getKillCount() { return killCount; }
    public void setKillCount(int count) { this.killCount = count; }
    public void addKill() { this.killCount++; }

    public int getTotalExpPoints() { return totalExpPoints; }
    public void setTotalExpPoints(int points) { this.totalExpPoints = points; }
    public void addExpPoints(int points) { this.totalExpPoints += points; }

    public NbtCompound getSavedInventory() { return savedInventory; }
    public void setSavedInventory(NbtCompound nbt) { this.savedInventory = nbt; }
    public NbtCompound getSavedEffects() { return savedEffects; }
    public void setSavedEffects(NbtCompound nbt) { this.savedEffects = nbt; }

    // PVP 字段访问
    public int getTrophyCount() { return trophyCount; }
    public void setTrophyCount(int count) { this.trophyCount = count; }
    public void addTrophy() { this.trophyCount++; }
    public Set<BlockPos> getPlacedBlocks() { return placedBlocks; }
    public int getArenaHalf() { return arenaHalf; }
    public int getArenaHeight() { return arenaHeight; }

    // 观众
    public List<UUID> getPvpSpectators() { return pvpSpectators; }
    public boolean isSpectator(UUID uuid) { return pvpSpectators.contains(uuid); }
    public void addSpectator(UUID uuid) { pvpSpectators.add(uuid); }
    public void removeSpectator(UUID uuid) { pvpSpectators.remove(uuid); }

    /** 获取所有PVP参与者(存活玩家 + 观众)，用于游戏结束统一处理 */
    public List<UUID> getAllPvpParticipants() {
        List<UUID> all = new ArrayList<>(pvpPlayers);
        all.addAll(pvpSpectators);
        return all;
    }

    // PVP玩家独立背包存取
    public void setSavedInventory(UUID playerUuid, NbtCompound nbt) { savedInventories.put(playerUuid, nbt); }
    public NbtCompound getSavedInventory(UUID playerUuid) { return savedInventories.get(playerUuid); }
    public void setSavedEffects(UUID playerUuid, NbtCompound nbt) { savedEffectsMap.put(playerUuid, nbt); }
    public NbtCompound getSavedEffects(UUID playerUuid) { return savedEffectsMap.get(playerUuid); }

    public long getLastFloorToggleTime() { return lastFloorToggleTime; }
    public void setLastFloorToggleTime(long time) { this.lastFloorToggleTime = time; }
    public long getFloorRestoreScheduledTime() { return floorRestoreScheduledTime; }
    public void setFloorRestoreScheduledTime(long time) { this.floorRestoreScheduledTime = time; }
    public boolean isFloorRemoved() { return floorRemoved; }
    public void setFloorRemoved(boolean removed) { this.floorRemoved = removed; }
    public long getLastBlockClearTime() { return lastBlockClearTime; }
    public void setLastBlockClearTime(long time) { this.lastBlockClearTime = time; }
    public long getLastBoundaryCheckTime() { return lastBoundaryCheckTime; }
    public void setLastBoundaryCheckTime(long time) { this.lastBoundaryCheckTime = time; }

    // 商人
    public UUID getMerchantUuid() { return merchantUuid; }
    public void setMerchantUuid(UUID uuid) { this.merchantUuid = uuid; }
    public int getMerchantTier() { return merchantTier; }
    public void setMerchantTier(int tier) { this.merchantTier = tier; }
    public boolean isUpgradePurchased() { return upgradePurchased; }
    public void setUpgradePurchased(boolean purchased) { this.upgradePurchased = purchased; }
    public int getPassCount() { return passCount; }
    public void setPassCount(int count) { this.passCount = count; }
    public void addPassCount(int count) { this.passCount += count; }

    // 方块商人
    public UUID getBlockMerchantUuid() { return blockMerchantUuid; }
    public void setBlockMerchantUuid(UUID uuid) { this.blockMerchantUuid = uuid; }
    public int getBlockMerchantTier() { return blockMerchantTier; }
    public void setBlockMerchantTier(int tier) { this.blockMerchantTier = tier; }
    public boolean isBlockMerchantUpgradePurchased() { return blockMerchantUpgradePurchased; }
    public void setBlockMerchantUpgradePurchased(boolean purchased) { this.blockMerchantUpgradePurchased = purchased; }

    // 药水商人
    public UUID getPotionMerchantUuid() { return potionMerchantUuid; }
    public void setPotionMerchantUuid(UUID uuid) { this.potionMerchantUuid = uuid; }
    public int getPotionMerchantTier() { return potionMerchantTier; }
    public void setPotionMerchantTier(int tier) { this.potionMerchantTier = tier; }
    public boolean isPotionMerchantUpgradePurchased() { return potionMerchantUpgradePurchased; }
    public void setPotionMerchantUpgradePurchased(boolean purchased) { this.potionMerchantUpgradePurchased = purchased; }

    // ===== 生命值/轮回 =====
    public int getLives() { return lives; }
    public void setLives(int lives) { this.lives = lives; }
    public void deductLife() { if (this.lives > 0) this.lives--; }
    public void addLife() { this.lives++; }

    public int getCycleCount() { return cycleCount; }
    public void setCycleCount(int count) { this.cycleCount = count; }
    public void incrementCycle() { this.cycleCount++; }

    /** 重置波次进度以开始新一轮轮回 */
    public void resetWaveProgress() {
        this.currentWaveIndex = 0;
        this.currentBatchIndex = 0;
        this.waves = WaveDefinition.getWaves();
    }

    /** 获取指定商人类型的等级 */
    public int getMerchantTier(int merchantType) {
        return switch (merchantType) {
            case 0 -> merchantTier;
            case 1 -> blockMerchantTier;
            case 2 -> potionMerchantTier;
            default -> merchantTier;
        };
    }

    /** 设置指定商人类型的等级 */
    public void setMerchantTier(int merchantType, int tier) {
        switch (merchantType) {
            case 0 -> this.merchantTier = tier;
            case 1 -> this.blockMerchantTier = tier;
            case 2 -> this.potionMerchantTier = tier;
        }
    }

    /** 获取指定商人类型的UUID */
    public UUID getMerchantUuid(int merchantType) {
        return switch (merchantType) {
            case 0 -> merchantUuid;
            case 1 -> blockMerchantUuid;
            case 2 -> potionMerchantUuid;
            default -> merchantUuid;
        };
    }

    /** 设置指定商人类型的UUID */
    public void setMerchantUuid(int merchantType, UUID uuid) {
        switch (merchantType) {
            case 0 -> this.merchantUuid = uuid;
            case 1 -> this.blockMerchantUuid = uuid;
            case 2 -> this.potionMerchantUuid = uuid;
        }
    }

    /** 清除所有商人UUID */
    public void clearAllMerchantUuids() {
        this.merchantUuid = null;
        this.blockMerchantUuid = null;
        this.potionMerchantUuid = null;
    }

    /** 设置指定商人类型的升级状态 */
    public void setMerchantUpgradePurchased(int merchantType, boolean purchased) {
        switch (merchantType) {
            case 0 -> this.upgradePurchased = purchased;
            case 1 -> this.blockMerchantUpgradePurchased = purchased;
            case 2 -> this.potionMerchantUpgradePurchased = purchased;
        }
    }

    public WaveDefinition.Wave getCurrentWave() {
        if (currentWaveIndex < waves.size()) return waves.get(currentWaveIndex);
        return null;
    }

    public WaveDefinition.Batch getCurrentBatch() {
        WaveDefinition.Wave wave = getCurrentWave();
        if (wave != null && currentBatchIndex < wave.batches().size()) {
            return wave.batches().get(currentBatchIndex);
        }
        return null;
    }

    public boolean advanceToNextBatch() {
        WaveDefinition.Wave wave = getCurrentWave();
        if (wave == null) return false;
        currentBatchIndex++;
        if (currentBatchIndex >= wave.batches().size()) {
            currentWaveIndex++;
            currentBatchIndex = 0;
            if (currentWaveIndex >= waves.size()) {
                state = GameState.VICTORY;
                return false;
            }
            state = GameState.WAVE_COMPLETE;
            return true;
        }
        return true;
    }

    public boolean isPVP() { return gameMode == GameMode.PVP; }
    public long getElapsedTime() { return System.currentTimeMillis() - startTime; }

    // ===== PVP 时间限制 =====
    public void setPvpStartTimeMs(long timeMs) { this.pvpStartTimeMs = timeMs; }
    public long getPvpStartTimeMs() { return pvpStartTimeMs; }
    public void setPvpTotalPlayerCount(int count) { this.pvpTotalPlayerCount = count; }
    public int getPvpTotalPlayerCount() { return pvpTotalPlayerCount; }

    /** 剩余毫秒（负数表示已超时） */
    public int getPvpRemainingMs() {
        if (pvpStartTimeMs == 0) return getPvpTimeLimitMillis();
        long elapsed = System.currentTimeMillis() - pvpStartTimeMs;
        return (int) Math.max(0, getPvpTimeLimitMillis() - elapsed);
    }

    /** 时间进度 0.0~1.0（0=刚开始, 1=时间到） */
    public float getPvpTimeProgress() {
        if (pvpStartTimeMs == 0) return 0f;
        long elapsed = System.currentTimeMillis() - pvpStartTimeMs;
        return Math.min(1f, (float) elapsed / getPvpTimeLimitMillis());
    }

    /** 存活玩家人数 */
    public int getPvpAliveCount(MinecraftServer server) {
        if (server == null) return 0;
        int alive = 0;
        for (UUID uid : pvpPlayers) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uid);
            if (p != null && p.isAlive()) alive++;
        }
        return alive;
    }
}
