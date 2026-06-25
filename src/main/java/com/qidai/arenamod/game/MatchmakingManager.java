package com.qidai.arenamod.game;

import com.qidai.arenamod.ArenaMod;
import com.qidai.arenamod.config.ArenaConfig;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * PVP matchmaking queue — reads min/max players and timeout from ArenaConfig
 */
public class MatchmakingManager {
    private static MatchmakingManager INSTANCE;

    private final Queue<ServerPlayerEntity> queue = new LinkedList<>();
    private boolean checking = false;

    private MatchmakingManager() {}

    public static MatchmakingManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new MatchmakingManager();
        }
        return INSTANCE;
    }

    /**
     * Join the matchmaking queue
     */
    public void addToQueue(ServerPlayerEntity player) {
        if (queue.contains(player)) {
            player.sendMessage(Text.translatable("message.arenamod.already_in_queue").formatted(Formatting.YELLOW), false);
            return;
        }

        queue.add(player);
        int minPlayers = ArenaConfig.getInstance().getMatchmakingMinPlayers();
        player.sendMessage(Text.translatable("message.arenamod.joined_queue").formatted(Formatting.GREEN), false);
        ArenaMod.LOGGER.info("Player {} joined PvP queue, size: {} (min: {})", player.getName().getString(), queue.size(), minPlayers);

        if (queue.size() >= minPlayers && !checking) {
            startMatch();
        }
    }

    /**
     * Remove from queue
     */
    public void removeFromQueue(ServerPlayerEntity player) {
        queue.remove(player);
    }

    /**
     * Start a match
     */
    private void startMatch() {
        checking = true;
        var config = ArenaConfig.getInstance();
        int minPlayers = config.getMatchmakingMinPlayers();
        int maxPlayers = config.getMatchmakingMaxPlayers();

        List<ServerPlayerEntity> matchPlayers = new ArrayList<>();
        while (!queue.isEmpty() && matchPlayers.size() < maxPlayers) {
            ServerPlayerEntity player = queue.poll();
            if (player != null && player.isAlive()) {
                matchPlayers.add(player);
            }
        }

        if (matchPlayers.size() >= minPlayers) {
            ArenaMod.LOGGER.info("PvP match started, players: {}", matchPlayers.size());

            for (ServerPlayerEntity player : matchPlayers) {
                player.sendMessage(Text.translatable("message.arenamod.match_found", matchPlayers.size()).formatted(Formatting.GREEN), false);
            }

            GameManager.getInstance().startPVPGame(matchPlayers);
        } else {
            // Not enough players, put them back
            queue.addAll(matchPlayers);
            ArenaMod.LOGGER.info("PvP queue: not enough players, waiting");
        }

        checking = false;
    }

    public int getQueueSize() {
        return queue.size();
    }
}
