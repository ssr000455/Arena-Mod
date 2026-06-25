package com.qidai.arenamod.config;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.qidai.arenamod.ArenaMod;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Arena Mod configuration — saved as arenamod/configuration/arenamod.json
 *
 * All config keys with their defaults:
 *
 * Arena:
 *   arena.size.singlePlayer (40)       - Half side length of single-player arena
 *   arena.size.pvp (15)                - Half side length of PvP arena
 *   arena.height.singlePlayer (100)    - Height limit for single-player arena
 *   arena.height.pvp (80)              - Height limit for PvP arena
 *   arena.gameTime (300)               - PvP time limit in seconds
 *   arena.keepInventoryOnDeath (false) - Keep inventory on death in arena
 *   arena.monsterDrops (true)          - Whether monsters drop items
 *
 * Gameplay:
 *   gameplay.lives.initial (30)        - Initial lives for single-player mode
 *   gameplay.wave.startDelay (100)     - Ticks before first wave starts
 *   gameplay.wave.resumeDelay (60)     - Ticks before wave starts on save resume
 *   gameplay.wave.batchCheckInterval (40) - Ticks between batch completion checks
 *   gameplay.wave.batchDelay (40)      - Ticks between batches within a wave
 *   gameplay.wave.cycleBonusExp (200)  - EXP awarded on cycle completion
 *   gameplay.wave.cycleBonusTrophies (1) - Trophies awarded on cycle completion
 *
 * PvP:
 *   pvp.floor.toggleInterval (1200)    - Ticks between floor removal (60s at 20tps)
 *   pvp.floor.restoreDelay (200)       - Ticks before floor is restored (10s)
 *   pvp.block.clearInterval (6000)     - Ticks between block area clears (5min)
 *   pvp.block.maxPlaced (100)          - Max blocks a player can place in PvP
 *   pvp.award.expOnElimination (20)    - EXP given when an opponent is eliminated
 *   pvp.award.expOnKill (20)           - EXP given for a PvP kill
 *   pvp.award.trophyOnKill (1)         - Trophies given for a PvP kill
 *   pvp.award.winnerExp (300)          - EXP given to the sole survivor/winner
 *   pvp.award.winnerTrophy (1)         - Trophies given to the winner
 *   pvp.award.drawExp (50)             - EXP given to each survivor on time-up draw
 *
 * Merchant:
 *   merchant.upgradeCostTier1 (60)     - EXP cost to upgrade merchant to tier 2
 *   merchant.upgradeCostTier2 (200)    - EXP cost to upgrade merchant to tier 3
 *
 * Matchmaking:
 *   matchmaking.minPlayers (2)         - Min players to start a PvP game
 *   matchmaking.maxPlayers (4)         - Max players per PvP game
 *   matchmaking.queueTimeout (120)     - Queue timeout in seconds
 *
 * Music:
 *   music.directory ("arenamod/music") - Music file directory
 */
public class ArenaConfig {
    private static ArenaConfig INSTANCE;
    private JsonObject root;
    private Path configPath;

    private static final String DEFAULT_CONFIG = """
            {
              "arena": {
                "size": {
                  "singlePlayer": 40,
                  "pvp": 15
                },
                "height": {
                  "singlePlayer": 100,
                  "pvp": 80
                },
                "gameTime": 300,
                "keepInventoryOnDeath": false,
                "monsterDrops": true
              },
              "gameplay": {
                "lives": {
                  "initial": 30
                },
                "wave": {
                  "startDelay": 100,
                  "resumeDelay": 60,
                  "batchCheckInterval": 40,
                  "batchDelay": 40,
                  "cycleBonusExp": 200,
                  "cycleBonusTrophies": 1
                }
              },
              "pvp": {
                "floor": {
                  "toggleInterval": 1200,
                  "restoreDelay": 200
                },
                "block": {
                  "clearInterval": 6000,
                  "maxPlaced": 100
                },
                "award": {
                  "expOnElimination": 20,
                  "expOnKill": 20,
                  "trophyOnKill": 1,
                  "winnerExp": 300,
                  "winnerTrophy": 1,
                  "drawExp": 50
                }
              },
              "merchant": {
                "upgradeCostTier1": 60,
                "upgradeCostTier2": 200
              },
              "matchmaking": {
                "minPlayers": 2,
                "maxPlayers": 4,
                "queueTimeout": 120
              },
              "music": {
                "directory": "arenamod/music"
              }
            }
            """;

    private ArenaConfig() {}

    public static ArenaConfig getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ArenaConfig();
        }
        return INSTANCE;
    }

    /** Load config from the game run directory */
    public void load(Path runDir) {
        configPath = runDir.resolve("arenamod").resolve("configuration").resolve("arenamod.json");
        try {
            Files.createDirectories(configPath.getParent());
        } catch (IOException e) {
            ArenaMod.LOGGER.warn("Failed to create config directory: {}", configPath.getParent());
        }

        if (Files.exists(configPath)) {
            try (FileReader reader = new FileReader(configPath.toFile())) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
                ArenaMod.LOGGER.info("Config loaded: {}", configPath);
            } catch (Exception e) {
                ArenaMod.LOGGER.warn("Failed to parse config, using defaults: {}", e.getMessage());
                root = null;
            }
        }

        if (root == null) {
            root = JsonParser.parseString(DEFAULT_CONFIG).getAsJsonObject();
            save();
        }

        // Validate config and log warnings
        var warnings = validate();
        for (String warning : warnings) {
            ArenaMod.LOGGER.warn("Config warning: {}", warning);
        }
    }

    /** Save current config to file */
    public void save() {
        if (configPath == null || root == null) return;
        try (FileWriter writer = new FileWriter(configPath.toFile())) {
            new GsonBuilder().setPrettyPrinting().create().toJson(root, writer);
            ArenaMod.LOGGER.info("Config saved: {}", configPath);
        } catch (IOException e) {
            ArenaMod.LOGGER.warn("Failed to save config: {}", e.getMessage());
        }
    }

    /** Whether config has been loaded */
    public boolean isLoaded() { return root != null; }

    /** Reload config from disk */
    public void reload(Path runDir) {
        load(runDir);
    }

    // ===== Arena =====

    /** Single-player arena half side length */
    public int getArenaHalf() {
        return getInt("arena.size.singlePlayer", 40);
    }

    /** PvP arena half side length */
    public int getPvpArenaHalf() {
        return getInt("arena.size.pvp", 15);
    }

    /** Single-player arena height limit */
    public int getArenaHeight() {
        return getInt("arena.height.singlePlayer", 100);
    }

    /** PvP arena height limit */
    public int getPvpArenaHeight() {
        return getInt("arena.height.pvp", 80);
    }

    /** PvP game time limit in seconds */
    public int getGameTime() {
        return getInt("arena.gameTime", 300);
    }

    /** Keep inventory on death in arena */
    public boolean isKeepInventoryOnDeath() {
        return getBool("arena.keepInventoryOnDeath", false);
    }

    /** Whether monsters drop items in arena */
    public boolean isMonsterDrops() {
        return getBool("arena.monsterDrops", true);
    }

    // ===== Gameplay =====

    /** Initial lives for single-player mode */
    public int getInitialLives() {
        return getInt("gameplay.lives.initial", 30);
    }

    /** Ticks before the first wave starts */
    public int getWaveStartDelay() {
        return getInt("gameplay.wave.startDelay", 100);
    }

    /** Ticks before wave starts when resuming a save */
    public int getWaveResumeDelay() {
        return getInt("gameplay.wave.resumeDelay", 60);
    }

    /** Ticks between batch completion checks */
    public int getBatchCheckInterval() {
        return getInt("gameplay.wave.batchCheckInterval", 40);
    }

    /** Ticks between batches within a wave */
    public int getWaveBatchDelay() {
        return getInt("gameplay.wave.batchDelay", 40);
    }

    /** EXP awarded when a cycle is completed */
    public int getCycleBonusExp() {
        return getInt("gameplay.wave.cycleBonusExp", 200);
    }

    /** Trophies awarded when a cycle is completed */
    public int getCycleBonusTrophies() {
        return getInt("gameplay.wave.cycleBonusTrophies", 1);
    }

    // ===== PvP =====

    /** Ticks between floor removal cycles */
    public int getPvpFloorToggleInterval() {
        return getInt("pvp.floor.toggleInterval", 1200);
    }

    /** Ticks before floor is restored after removal */
    public int getPvpFloorRestoreDelay() {
        return getInt("pvp.floor.restoreDelay", 200);
    }

    /** Ticks between block area clears */
    public int getPvpBlockClearInterval() {
        return getInt("pvp.block.clearInterval", 6000);
    }

    /** Max blocks a player can place in PvP arena */
    public int getPvpMaxPlacedBlocks() {
        return getInt("pvp.block.maxPlaced", 100);
    }

    /** EXP given when an opponent is eliminated (boundary/spectate) */
    public int getPvpExpOnElimination() {
        return getInt("pvp.award.expOnElimination", 20);
    }

    /** EXP given for a PvP kill */
    public int getPvpExpOnKill() {
        return getInt("pvp.award.expOnKill", 20);
    }

    /** Trophies given for a PvP kill */
    public int getPvpTrophyOnKill() {
        return getInt("pvp.award.trophyOnKill", 1);
    }

    /** EXP given to the sole survivor when time runs out */
    public int getPvpWinnerExp() {
        return getInt("pvp.award.winnerExp", 300);
    }

    /** Trophies given to the winner */
    public int getPvpWinnerTrophy() {
        return getInt("pvp.award.winnerTrophy", 1);
    }

    /** EXP given to each survivor on a time-up draw */
    public int getPvpDrawExp() {
        return getInt("pvp.award.drawExp", 50);
    }

    // ===== Merchant =====

    /** EXP cost to upgrade merchant to tier 2 */
    public int getMerchantUpgradeCostTier1() {
        return getInt("merchant.upgradeCostTier1", 60);
    }

    /** EXP cost to upgrade merchant to tier 3 */
    public int getMerchantUpgradeCostTier2() {
        return getInt("merchant.upgradeCostTier2", 200);
    }

    // ===== Matchmaking =====

    /** Min players to start a PvP game */
    public int getMatchmakingMinPlayers() {
        return getInt("matchmaking.minPlayers", 2);
    }

    /** Max players per PvP game */
    public int getMatchmakingMaxPlayers() {
        return getInt("matchmaking.maxPlayers", 4);
    }

    /** Queue timeout in seconds */
    public int getMatchmakingQueueTimeout() {
        return getInt("matchmaking.queueTimeout", 120);
    }

    // ===== Music =====

    /** Music file directory */
    public String getMusicDirectory() {
        return getString("music.directory", "arenamod/music");
    }

    // ===== Validation =====

    /**
     * Validate all config values and clamp to reasonable ranges.
     * Returns a list of warnings for invalid values.
     */
    public java.util.List<String> validate() {
        java.util.List<String> warnings = new java.util.ArrayList<>();

        if (getArenaHalf() < 5) { warnings.add("arena.size.singlePlayer is too small (< 5), using 5"); }
        if (getPvpArenaHalf() < 5) { warnings.add("arena.size.pvp is too small (< 5), using 5"); }
        if (getInitialLives() < 1) { warnings.add("gameplay.lives.initial must be >= 1"); }
        if (getGameTime() < 10) { warnings.add("arena.gameTime is too short (< 10s)"); }
        if (getMatchmakingMinPlayers() < 2) { warnings.add("matchmaking.minPlayers must be >= 2"); }
        if (getMatchmakingMaxPlayers() > 64) { warnings.add("matchmaking.maxPlayers is too high (> 64)"); }
        if (getWaveStartDelay() < 1) { warnings.add("gameplay.wave.startDelay must be >= 1"); }
        if (getPvpMaxPlacedBlocks() < 1) { warnings.add("pvp.block.maxPlaced must be >= 1"); }

        return warnings;
    }

    // ===== Generic readers =====

    private int getInt(String key, int def) {
        if (root == null) return def;
        String[] parts = key.split("\\.");
        JsonObject obj = root;
        for (int i = 0; i < parts.length - 1; i++) {
            if (obj == null || !obj.has(parts[i])) return def;
            obj = obj.getAsJsonObject(parts[i]);
        }
        if (obj == null || !obj.has(parts[parts.length - 1])) return def;
        return obj.get(parts[parts.length - 1]).getAsInt();
    }

    private boolean getBool(String key, boolean def) {
        if (root == null) return def;
        String[] parts = key.split("\\.");
        JsonObject obj = root;
        for (int i = 0; i < parts.length - 1; i++) {
            if (obj == null || !obj.has(parts[i])) return def;
            obj = obj.getAsJsonObject(parts[i]);
        }
        if (obj == null || !obj.has(parts[parts.length - 1])) return def;
        return obj.get(parts[parts.length - 1]).getAsBoolean();
    }

    private String getString(String key, String def) {
        if (root == null) return def;
        String[] parts = key.split("\\.");
        JsonObject obj = root;
        for (int i = 0; i < parts.length - 1; i++) {
            if (obj == null || !obj.has(parts[i])) return def;
            obj = obj.getAsJsonObject(parts[i]);
        }
        if (obj == null || !obj.has(parts[parts.length - 1])) return def;
        return obj.get(parts[parts.length - 1]).getAsString();
    }

    public JsonObject getRoot() { return root; }
}
