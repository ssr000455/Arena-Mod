package com.qidai.arenamod.game;

import com.qidai.arenamod.ArenaMod;
import com.qidai.arenamod.config.ArenaConfig;
import com.qidai.arenamod.dimension.ModDimensions;
import com.qidai.arenamod.item.ModItems;
import com.qidai.arenamod.network.ModNetworking;
import com.qidai.arenamod.wave.WaveDefinition;
import com.qidai.arenamod.wave.WaveSpawner;
import net.fabricmc.fabric.api.dimension.v1.FabricDimensions;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.GameRules;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class GameManager {
    private static GameManager INSTANCE;

    private final Map<UUID, GameInstance> activeGames = new ConcurrentHashMap<>();

    // 基于 tick 的任务调度器（替代 Thread.sleep）
    private final List<ScheduledTask> scheduledTasks = new CopyOnWriteArrayList<>();

    // 玩家进入竞技场前的原始位置缓存（退出时传送回此处）
    private final Map<UUID, SavedPosition> savedPositions = new ConcurrentHashMap<>();

    private GameManager() {}

    public static GameManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new GameManager();
        }
        return INSTANCE;
    }

    // ===== Tick 调度器 =====

    /** 每个服务端 tick 调用一次，驱动所有延迟任务 */
    public void tickScheduler() {
        List<Runnable> toExecute = new ArrayList<>();
        synchronized (scheduledTasks) {
            scheduledTasks.removeIf(task -> {
                task.ticksRemaining--;
                if (task.ticksRemaining <= 0) {
                    toExecute.add(task.action);
                    return true;
                }
                return false;
            });
        }
        // 在 removeIf 迭代之外执行任务回调，防止回调中修改 scheduledTasks 引发
        // CopyOnWriteArrayList 在 ART 上的 ConcurrentModificationException
        for (Runnable action : toExecute) {
            try {
                action.run();
            } catch (Exception e) {
                ArenaMod.LOGGER.error("调度任务执行异常", e);
            }
        }
    }

    /** 为指定玩家调度一个延迟任务（延迟单位为 tick） */
    private void scheduleTask(UUID playerUuid, int delayTicks, Runnable action) {
        // 原子操作：先取消旧任务再添加新任务
        synchronized (scheduledTasks) {
            scheduledTasks.removeIf(task -> task.playerUuid.equals(playerUuid));
            scheduledTasks.add(new ScheduledTask(playerUuid, delayTicks, action));
        }
    }

    /** 取消指定玩家的所有待执行任务 */
    private void cancelPlayerTasks(UUID playerUuid) {
        synchronized (scheduledTasks) {
            scheduledTasks.removeIf(task -> task.playerUuid.equals(playerUuid));
        }
    }

    private static class ScheduledTask {
        final UUID playerUuid;
        int ticksRemaining;
        final Runnable action;

        ScheduledTask(UUID playerUuid, int ticks, Runnable action) {
            this.playerUuid = playerUuid;
            this.ticksRemaining = ticks;
            this.action = action;
        }
    }

    // ===== 进度同步 =====

    /** 向客户端发送游戏进度信息（用于 HUD 显示） */
    private void syncProgressToClient(ServerPlayerEntity player, GameInstance game) {
        if (player == null || game == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        var buf = PacketByteBufs.create();
        buf.writeInt(game.getGameMode().ordinal()); // 0=单人, 1=PVP
        buf.writeInt(game.getCurrentWaveIndex());
        buf.writeInt(game.getCurrentBatchIndex());
        buf.writeBoolean(game.getCurrentWave() != null);
        if (game.getCurrentWave() != null) {
            buf.writeInt(game.getCurrentWave().batches().size());
        }
        buf.writeInt(game.getState().ordinal());
        buf.writeInt(game.getTotalExpPoints());
        buf.writeInt(game.getLives());
        // PVP 额外数据（含总时限）
        if (game.isPVP()) {
            buf.writeInt(game.getPvpRemainingMs() / 50);  // ms → ticks for client
            buf.writeInt(game.getPvpAliveCount(server));
            buf.writeInt(game.getPvpTotalPlayerCount());
            buf.writeInt(GameInstance.getPvpTimeLimitMillis() / 50);
        }
        ServerPlayNetworking.send(player, ModNetworking.GAME_PROGRESS_ID, buf);
    }

    // ===== 背包/效果管理 =====

    /** 保存玩家背包和效果到 GameInstance，然后清空 */
    private void saveAndClearPlayerItems(ServerPlayerEntity player, GameInstance game) {
        NbtCompound inventoryNbt = new NbtCompound();
        NbtList items = player.getInventory().writeNbt(new NbtList());
        inventoryNbt.put("items", items);

        NbtCompound effectsNbt = new NbtCompound();
        NbtList effects = new NbtList();
        player.getStatusEffects().forEach(effect -> {
            NbtCompound e = new NbtCompound();
            effect.writeNbt(e);
            effects.add(e);
        });
        effectsNbt.put("effects", effects);

        if (game.isPVP()) {
            // PVP模式：每人独立存档
            game.setSavedInventory(player.getUuid(), inventoryNbt);
            game.setSavedEffects(player.getUuid(), effectsNbt);
        } else {
            // 单人模式：共享存档（每个玩家各自有独立GameInstance）
            game.setSavedInventory(inventoryNbt);
            game.setSavedEffects(effectsNbt);
        }

        player.getInventory().clear();
        player.clearStatusEffects();
    }

    /** 在主世界生成箱子，归还玩家物品，恢复效果 */
    private void spawnReturnChestAndRestore(ServerPlayerEntity player, GameInstance game) {
        MinecraftServer server = player.getServer();
        if (server == null) return;

        NbtCompound invNbt;
        NbtCompound effNbt;

        if (game.isPVP()) {
            invNbt = game.getSavedInventory(player.getUuid());
            effNbt = game.getSavedEffects(player.getUuid());
        } else {
            invNbt = game.getSavedInventory();
            effNbt = game.getSavedEffects();
        }

        if (invNbt == null) return;

        ServerWorld overworld = server.getWorld(net.minecraft.world.World.OVERWORLD);
        if (overworld == null) return;

        BlockPos chestPos = player.getBlockPos().up(2);

        overworld.setBlockState(chestPos, Blocks.CHEST.getDefaultState());
        ChestBlockEntity chest = (ChestBlockEntity) overworld.getBlockEntity(chestPos);

        if (chest != null) {
            NbtList items = invNbt.getList("items", 10);
            for (int i = 0; i < items.size(); i++) {
                NbtCompound itemNbt = items.getCompound(i);
                int slot = itemNbt.getByte("Slot") & 255;
                if (slot < chest.size()) {
                    ItemStack stack = ItemStack.fromNbt(itemNbt);
                    if (!stack.isEmpty()) {
                        chest.setStack(slot, stack);
                    }
                }
            }
        }

        if (effNbt != null) {
            NbtList effects = effNbt.getList("effects", 10);
            for (int i = 0; i < effects.size(); i++) {
                NbtCompound e = effects.getCompound(i);
                StatusEffectInstance effect = StatusEffectInstance.fromNbt(e);
                if (effect != null) {
                    player.addStatusEffect(effect);
                }
            }
        }
    }

    // ===== 存档继续 =====

    public void resumeGame(ServerPlayerEntity player, GameSaveManager.GameSaveData saveData) {
        if (isPlayerInGame(player)) return;

        MinecraftServer server = player.getServer();
        if (server == null) return;

        ServerWorld arenaWorld = server.getWorld(ModDimensions.ARENA_WORLD_KEY);
        if (arenaWorld == null) return;

        ArenaBuilder.buildArena(arenaWorld, saveData.arenaCenter());

        // 根据配置文件设置竞技场维度规则
        arenaWorld.getGameRules().get(GameRules.DO_MOB_LOOT).set(ArenaConfig.getInstance().isMonsterDrops(), server);
        arenaWorld.getGameRules().get(GameRules.KEEP_INVENTORY).set(ArenaConfig.getInstance().isKeepInventoryOnDeath(), server);

        GameInstance game = new GameInstance(player, saveData.gameMode(), saveData.arenaCenter());

        for (int w = 0; w < saveData.waveIndex(); w++) game.advanceToNextBatch();
        for (int b = 0; b < saveData.batchIndex(); b++) game.advanceToNextBatch();

        game.setState(GameState.IN_PROGRESS);
        game.setTotalExpPoints(saveData.expPoints());
        game.setTrophyCount(saveData.trophyCount());
        game.setPassCount(saveData.passCount());
        game.setBlockMerchantTier(Math.max(1, saveData.blockMerchantTier()));
        game.setPotionMerchantTier(Math.max(1, saveData.potionMerchantTier()));
        game.setLives(Math.max(1, saveData.lives()));
        game.setCycleCount(Math.max(0, saveData.cycleCount()));
        activeGames.put(player.getUuid(), game);

        BlockPos spawnPos = saveData.arenaCenter();
        TeleportTarget target = new TeleportTarget(
                new Vec3d(spawnPos.getX() + 0.5, spawnPos.getY() + 1, spawnPos.getZ() + 0.5),
                Vec3d.ZERO, 0, 0
        );
        savePlayerPosition(player);
        FabricDimensions.teleport(player, arenaWorld, target);

        restoreExpItems(player, saveData.expPoints());
        player.sendMessage(Text.translatable("message.arenamod.save_loaded").formatted(Formatting.GREEN), false);

        applyArenaEffects(player);
        scheduleWaveStart(player, game, ArenaConfig.getInstance().getWaveResumeDelay());
    }

    // ===== 开始游戏 =====

    public void startSinglePlayerGame(ServerPlayerEntity player) {
        if (isPlayerInGame(player)) {
            player.sendMessage(Text.translatable("message.arenamod.already_in_game").formatted(Formatting.RED), false);
            return;
        }

        MinecraftServer server = player.getServer();
        if (server == null) return;

        ServerWorld arenaWorld = server.getWorld(ModDimensions.ARENA_WORLD_KEY);
        if (arenaWorld == null) {
            player.sendMessage(Text.translatable("message.arenamod.arena_dimension_unloaded").formatted(Formatting.RED), false);
            return;
        }

        int offsetX = (int) (Math.random() * 10000) - 5000;
        BlockPos arenaCenter = new BlockPos(offsetX, ArenaBuilder.getFloorY() + 1, 0);

        ArenaBuilder.buildArena(arenaWorld, arenaCenter);

        // 根据配置文件设置竞技场维度规则
        arenaWorld.getGameRules().get(GameRules.DO_MOB_LOOT).set(ArenaConfig.getInstance().isMonsterDrops(), server);
        arenaWorld.getGameRules().get(GameRules.KEEP_INVENTORY).set(ArenaConfig.getInstance().isKeepInventoryOnDeath(), server);

        GameInstance game = new GameInstance(player, GameMode.SINGLE_PLAYER, arenaCenter);
        game.setState(GameState.IN_PROGRESS);
        activeGames.put(player.getUuid(), game);

        saveAndClearPlayerItems(player, game);

        BlockPos spawnPos = arenaCenter;
        TeleportTarget target = new TeleportTarget(
                new Vec3d(spawnPos.getX() + 0.5, spawnPos.getY() + 1, spawnPos.getZ() + 0.5),
                Vec3d.ZERO, 0, 0
        );
        savePlayerPosition(player);
        FabricDimensions.teleport(player, arenaWorld, target);

        applyArenaEffects(player);

        player.sendMessage(Text.translatable("message.arenamod.single_player_start").formatted(Formatting.GREEN), false);
        player.sendMessage(Text.translatable("message.arenamod.lives_remaining", game.getLives()).formatted(Formatting.YELLOW), false);
        player.sendMessage(Text.translatable("message.arenamod.first_wave_coming").formatted(Formatting.YELLOW), false);

        scheduleWaveStart(player, game, ArenaConfig.getInstance().getWaveStartDelay());
    }

    /** 给玩家施加夜视效果（竞技场围蔽导致黑暗） */
    private void applyArenaEffects(ServerPlayerEntity player) {
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 999999, 0, false, false));
    }

    public void joinPVPQueue(ServerPlayerEntity player) {
        MatchmakingManager.getInstance().addToQueue(player);
    }

    public void startPVPGame(List<ServerPlayerEntity> players) {
        MinecraftServer server = players.get(0).getServer();
        if (server == null) return;

        ServerWorld arenaWorld = server.getWorld(ModDimensions.ARENA_WORLD_KEY);
        if (arenaWorld == null) return;

        int offsetX = (int) (Math.random() * 10000) - 5000;
        BlockPos arenaCenter = new BlockPos(offsetX, ArenaBuilder.getFloorY() + 1, 0);
        ArenaBuilder.buildPvpArena(arenaWorld, arenaCenter);

        GameInstance game = new GameInstance(players.get(0), GameMode.PVP, arenaCenter);
        game.setState(GameState.IN_PROGRESS);

        long worldTime = arenaWorld.getTime();
        game.setLastFloorToggleTime(worldTime);
        game.setLastBlockClearTime(worldTime);
        game.setPvpStartTimeMs(System.currentTimeMillis());
        game.setPvpTotalPlayerCount(players.size());    // 记录总人数

        for (ServerPlayerEntity player : players) {
            game.getPvpPlayers().add(player.getUuid());
            activeGames.put(player.getUuid(), game);

            saveAndClearPlayerItems(player, game);

            BlockPos spawnPos = arenaCenter;
            TeleportTarget target = new TeleportTarget(
                    new Vec3d(spawnPos.getX() + 0.5, spawnPos.getY() + 1, spawnPos.getZ() + 0.5),
                    Vec3d.ZERO, 0, 0
            );
            savePlayerPosition(player);
            FabricDimensions.teleport(player, arenaWorld, target);

            applyArenaEffects(player);

            player.sendMessage(Text.translatable("message.arenamod.pvp_start", 1200).formatted(Formatting.RED), false);
        }
    }

    // ===== 波次调度 =====

    private void scheduleWaveStart(ServerPlayerEntity player, GameInstance game, int delayTicks) {
        scheduleTask(player.getUuid(), delayTicks, () -> {
            spawnNextBatch(player, game);
        });
    }

    public void spawnNextBatch(ServerPlayerEntity player, GameInstance game) {
        if (!activeGames.containsKey(player.getUuid())) return;
        if (game.getState() == GameState.VICTORY || game.getState() == GameState.DEFEAT) return;

        MinecraftServer server = player.getServer();
        if (server == null) return;

        ServerWorld arenaWorld = server.getWorld(ModDimensions.ARENA_WORLD_KEY);
        if (arenaWorld == null) return;

        WaveDefinition.Batch batch = game.getCurrentBatch();
        if (batch == null) {
            game.setState(GameState.VICTORY);
            player.sendMessage(Text.translatable("message.arenamod.all_waves_cleared").formatted(Formatting.GOLD), false);
            endGame(player, game);
            return;
        }

        game.setState(GameState.BATCH_COOLDOWN);

        int waveNum = game.getCurrentWaveIndex() + 1;
        int batchNum = game.getCurrentBatchIndex() + 1;
        int totalBatches = game.getCurrentWave().batches().size();

        player.sendMessage(Text.translatable("message.arenamod.wave_batch_info", waveNum, batchNum, totalBatches)
                .formatted(Formatting.AQUA), false);

        List<MobEntity> spawned = WaveSpawner.spawnBatch(arenaWorld, game, batch);
        game.getCurrentBatchMobs().clear();
        game.getCurrentBatchMobs().addAll(spawned);

        player.sendMessage(Text.translatable("message.arenamod.monsters_spawned", spawned.size()).formatted(Formatting.GRAY), false);

        game.setState(GameState.IN_PROGRESS);
        syncProgressToClient(player, game);
        scheduleBatchCheck(player, game);
    }

    private void scheduleBatchCheck(ServerPlayerEntity player, GameInstance game) {
        int interval = ArenaConfig.getInstance().getBatchCheckInterval();
        scheduleTask(player.getUuid(), interval, () -> {
            checkBatchCompletion(player, game);
        });
    }

    private void checkBatchCompletion(ServerPlayerEntity player, GameInstance game) {
        if (!activeGames.containsKey(player.getUuid())) return;
        if (game.getState() == GameState.VICTORY || game.getState() == GameState.DEFEAT) return;

        List<MobEntity> mobs = game.getCurrentBatchMobs();
        mobs.removeIf(m -> !m.isAlive());

        if (mobs.isEmpty()) {
            player.sendMessage(Text.translatable("message.arenamod.batch_cleared").formatted(Formatting.GREEN), false);
            player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 1.0f, 1.0f);

            boolean hasNext = game.advanceToNextBatch();
            if (!hasNext && game.getState() == GameState.VICTORY) {
                game.incrementCycle();
                int cycle = game.getCycleCount();
                int bonusExp = ArenaConfig.getInstance().getCycleBonusExp();
                int bonusTrophies = ArenaConfig.getInstance().getCycleBonusTrophies();
                for (int t = 0; t < bonusTrophies; t++) game.addTrophy();
                game.addExpPoints(bonusExp);
                if (bonusExp > 0) {
                    player.getInventory().offerOrDrop(new ItemStack(ModItems.EXP_POINT, bonusExp));
                }
                player.sendMessage(Text.translatable("message.arenamod.cycle_complete", cycle, bonusExp, bonusTrophies)
                        .formatted(Formatting.GOLD), false);
                GameSaveManager.getInstance().deleteSave(player.getServer(), player.getUuid());

                game.resetWaveProgress();
                game.setState(GameState.IN_PROGRESS);
                player.sendMessage(Text.translatable("message.arenamod.new_cycle_start").formatted(Formatting.GREEN), false);
                syncProgressToClient(player, game);
                scheduleWaveStart(player, game, ArenaConfig.getInstance().getWaveStartDelay());
            } else if (game.getState() == GameState.WAVE_COMPLETE) {
                int nextWave = game.getCurrentWaveIndex() + 1;
                MinecraftServer server = player.getServer();
                if (server != null) {
                    ServerWorld arenaWorld = server.getWorld(ModDimensions.ARENA_WORLD_KEY);
                    if (arenaWorld != null) {
                        ArenaBuilder.removeAllMerchants(arenaWorld, game);
                        game.clearAllMerchantUuids();
                    }
                }
                player.sendMessage(Text.translatable("message.arenamod.wave_complete", game.getCurrentWaveIndex(), nextWave)
                        .formatted(Formatting.LIGHT_PURPLE), false);
                player.sendMessage(Text.translatable("message.arenamod.resummon_merchant_hint")
                        .formatted(Formatting.YELLOW), false);
                syncProgressToClient(player, game);
                scheduleWaveStart(player, game, ArenaConfig.getInstance().getWaveStartDelay());
            } else {
                syncProgressToClient(player, game);
                scheduleWaveStart(player, game, ArenaConfig.getInstance().getWaveBatchDelay());
            }
        } else {
            scheduleBatchCheck(player, game);
        }
    }

    // ===== EXP 物品管理 =====

    public void clearExpItems(ServerPlayerEntity player) {
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isOf(ModItems.EXP_POINT)) {
                player.getInventory().setStack(i, ItemStack.EMPTY);
            }
        }
    }

    public void restoreExpItems(ServerPlayerEntity player, int expPoints) {
        if (expPoints <= 0) return;
        final int MAX_STACK = 9999;
        while (expPoints > 0) {
            int amount = Math.min(expPoints, MAX_STACK);
            player.getInventory().offerOrDrop(new ItemStack(ModItems.EXP_POINT, amount));
            expPoints -= amount;
        }
    }

    // ===== 商人交互 =====

    public void openMerchant(ServerPlayerEntity player, GameInstance game, int merchantType) {
        var buf = PacketByteBufs.create();
        int tier = game.getMerchantTier(merchantType);
        buf.writeInt(tier);
        buf.writeBoolean(tier < 3);
        buf.writeInt(merchantType);
        ServerPlayNetworking.send(player, ModNetworking.OPEN_MERCHANT_ID, buf);
    }

    public void handleMerchantBuy(ServerPlayerEntity player, int itemIndex, int merchantType) {
        GameInstance game = activeGames.get(player.getUuid());
        if (game == null || game.getGameMode() != GameMode.SINGLE_PLAYER) return;

        if (itemIndex == -1) {
            handleMerchantUpgrade(player, game, merchantType);
            return;
        }

        int tier = game.getMerchantTier(merchantType);
        var items = MerchantData.getItemsForMerchant(merchantType, tier);
        if (itemIndex < 0 || itemIndex >= items.size()) return;

        MerchantData.MerchantItem merchantItem = items.get(itemIndex);

        int expCount = countExpItems(player);
        if (expCount < merchantItem.expCost()) {
            player.sendMessage(Text.translatable("message.arenamod.exp_not_enough", merchantItem.expCost())
                    .formatted(Formatting.RED), false);
            return;
        }

        removeExpItems(player, merchantItem.expCost());
        game.addExpPoints(-merchantItem.expCost());

        ItemStack bought = merchantItem.item().copy();
        player.getInventory().offerOrDrop(bought);

        if (merchantType == MerchantData.MERCHANT_TYPE_ITEM && MerchantData.isPassItem(bought)) {
            game.addPassCount(bought.getCount());
        }

        player.sendMessage(Text.translatable("message.arenamod.purchase_success", merchantItem.expCost())
                .formatted(Formatting.GREEN), false);
    }

    private void handleMerchantUpgrade(ServerPlayerEntity player, GameInstance game, int merchantType) {
        int currentTier = game.getMerchantTier(merchantType);
        if (currentTier >= 3) {
            player.sendMessage(Text.translatable("message.arenamod.max_tier_reached").formatted(Formatting.RED), false);
            return;
        }

        int cost = MerchantData.getUpgradeCost(currentTier);
        int expCount = countExpItems(player);
        if (expCount < cost) {
            player.sendMessage(Text.translatable("message.arenamod.exp_not_enough_upgrade", cost)
                    .formatted(Formatting.RED), false);
            return;
        }

        removeExpItems(player, cost);
        game.addExpPoints(-cost);

        int newTier = currentTier + 1;
        String merchantName = MerchantData.getMerchantName(merchantType);
        String tierName = switch (newTier) {
            case 2 -> net.minecraft.text.Text.translatable("merchant.arenamod.tier.2").getString();
            case 3 -> net.minecraft.text.Text.translatable("merchant.arenamod.tier.3").getString();
            default -> net.minecraft.text.Text.translatable("merchant.arenamod.tier.unknown").getString();
        };

        game.setMerchantTier(merchantType, newTier);
        game.setMerchantUpgradePurchased(merchantType, false);
        player.sendMessage(Text.translatable("message.arenamod.merchant_upgraded", merchantName, tierName).formatted(Formatting.GREEN), false);
    }

    private int countExpItems(ServerPlayerEntity player) {
        int count = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isOf(ModItems.EXP_POINT)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private void removeExpItems(ServerPlayerEntity player, int amount) {
        for (int i = 0; i < player.getInventory().size() && amount > 0; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isOf(ModItems.EXP_POINT)) {
                int toRemove = Math.min(amount, stack.getCount());
                stack.decrement(toRemove);
                amount -= toRemove;
            }
        }
    }

    // ===== 商人召唤 =====

    public void spawnPlayerMerchants(ServerPlayerEntity player) {
        GameInstance game = activeGames.get(player.getUuid());
        if (game == null || game.getGameMode() != GameMode.SINGLE_PLAYER) {
            player.sendMessage(Text.translatable("message.arenamod.not_in_single_player").formatted(Formatting.RED), false);
            return;
        }

        MinecraftServer server = player.getServer();
        if (server == null) return;

        ServerWorld arenaWorld = server.getWorld(ModDimensions.ARENA_WORLD_KEY);
        if (arenaWorld == null) return;

        BlockPos playerPos = player.getBlockPos();

        ArenaBuilder.removeAllMerchants(arenaWorld, game);
        game.clearAllMerchantUuids();

        BlockPos[] offsets = {
                new BlockPos(-2, 0, 0),
                new BlockPos(2, 0, 0),
                new BlockPos(0, 0, 2)
        };

        var itemMerchant = ArenaBuilder.spawnMerchant(arenaWorld, playerPos.add(offsets[0]));
        if (itemMerchant != null) {
            game.setMerchantUuid(MerchantData.MERCHANT_TYPE_ITEM, itemMerchant.getUuid());
        }

        var blockMerchant = ArenaBuilder.spawnBlockMerchant(arenaWorld, playerPos.add(offsets[1]));
        if (blockMerchant != null) {
            game.setMerchantUuid(MerchantData.MERCHANT_TYPE_BLOCK, blockMerchant.getUuid());
        }

        var potionMerchant = ArenaBuilder.spawnPotionMerchant(arenaWorld, playerPos.add(offsets[2]));
        if (potionMerchant != null) {
            game.setMerchantUuid(MerchantData.MERCHANT_TYPE_POTION, potionMerchant.getUuid());
        }

        player.sendMessage(Text.translatable("message.arenamod.merchant_summoned").formatted(Formatting.GREEN), false);
    }

    // ===== PVP Tick =====

    public void tickArenaWorld(ServerWorld world) {
        long gameTime = world.getTime();
        Set<GameInstance> processedGames = new HashSet<>();
        MinecraftServer server = world.getServer();

        for (GameInstance game : activeGames.values()) {
            if (!processedGames.add(game)) continue;
            if (game.getGameMode() != GameMode.PVP) continue;
            if (game.getState() == GameState.VICTORY || game.getState() == GameState.DEFEAT) continue;

            tickPvpGame(world, game, gameTime);

            // PVP 时限检查
            if (game.getPvpRemainingMs() <= 0) {
                endPvpByTimeLimit(server, game);
                continue;
            }
        }

        // 每秒同步一次进度（PVP实时更新倒计时和人数）
        if (gameTime % 20 == 0 && server != null) {
            Set<GameInstance> syncedGames = new HashSet<>();
            for (Map.Entry<UUID, GameInstance> entry : activeGames.entrySet()) {
                GameInstance game = entry.getValue();
                if (game.getState() == GameState.VICTORY || game.getState() == GameState.DEFEAT) continue;
                if (game.getGameMode() == GameMode.PVP && syncedGames.add(game)) {
                    for (UUID uid : game.getAllPvpParticipants()) {
                        ServerPlayerEntity p = server.getPlayerManager().getPlayer(uid);
                        if (p != null) syncProgressToClient(p, game);
                    }
                }
            }

            // 边界检查
            for (Map.Entry<UUID, GameInstance> entry : activeGames.entrySet()) {
                GameInstance game = entry.getValue();
                if (game.getState() == GameState.VICTORY || game.getState() == GameState.DEFEAT) continue;
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
                if (player != null && player.getWorld().getRegistryKey().equals(ModDimensions.ARENA_WORLD_KEY)) {
                    checkPlayerBoundary(player, game);
                }
            }
        }
    }

    private void tickPvpGame(ServerWorld world, GameInstance game, long gameTime) {
        var config = ArenaConfig.getInstance();
        if (!game.isFloorRemoved()) {
            if (gameTime - game.getLastFloorToggleTime() >= config.getPvpFloorToggleInterval()) {
                ArenaBuilder.removeFloor(world, game.getArenaCenter(), game.getArenaHalf());
                game.setFloorRemoved(true);
                game.setFloorRestoreScheduledTime(gameTime);
                game.setLastFloorToggleTime(gameTime);
            }
        } else {
            if (gameTime - game.getFloorRestoreScheduledTime() >= config.getPvpFloorRestoreDelay()) {
                ArenaBuilder.restoreFloor(world, game.getArenaCenter(), game.getArenaHalf());
                game.setFloorRemoved(false);
                game.setLastFloorToggleTime(gameTime);
            }
        }

        if (gameTime - game.getLastBlockClearTime() >= config.getPvpBlockClearInterval()) {
            ArenaBuilder.clearNonStructuralBlocks(world, game.getArenaCenter(), game.getArenaHalf(), game.getArenaHeight());
            game.getPlacedBlocks().clear();
            game.setLastBlockClearTime(gameTime);
        }
    }

    private void checkPlayerBoundary(ServerPlayerEntity player, GameInstance game) {
        BlockPos playerPos = player.getBlockPos();
        BlockPos center = game.getArenaCenter();
        int half = game.getArenaHalf();

        if (!ArenaBuilder.isInBounds(playerPos, center, half)) {
            if (game.getGameMode() == GameMode.PVP) {
                // 已在观战模式中则跳过
                if (game.isSpectator(player.getUuid())) return;

                player.sendMessage(Text.translatable("message.arenamod.escaped_arena_fail").formatted(Formatting.RED), false);

                // 掉落所有物品（死亡掉落）
                for (int i = 0; i < player.getInventory().size(); i++) {
                    ItemStack stack = player.getInventory().getStack(i);
                    if (!stack.isEmpty()) {
                        player.dropItem(stack, true, false);
                    }
                }
                player.getInventory().clear();

                game.getPvpPlayers().remove(player.getUuid());
                game.addSpectator(player.getUuid());
                cancelPlayerTasks(player.getUuid());

                // 设置观战模式并传送回竞技场中心
                player.changeGameMode(net.minecraft.world.GameMode.SPECTATOR);
                MinecraftServer server = player.getServer();
                if (server != null) {
                    ServerWorld arenaWorld = server.getWorld(ModDimensions.ARENA_WORLD_KEY);
                    if (arenaWorld != null) {
                        BlockPos c = game.getArenaCenter();
                        TeleportTarget target = new TeleportTarget(
                                new Vec3d(c.getX() + 0.5, c.getY() + 1, c.getZ() + 0.5),
                                Vec3d.ZERO, 0, 0
                        );
                        FabricDimensions.teleport(player, arenaWorld, target);
                    }
                    awardAllPvpPlayersExcept(server, game, player.getUuid(), ArenaConfig.getInstance().getPvpExpOnElimination());
                    checkPvpWinner(server, game);
                }
            } else {
                player.sendMessage(Text.translatable("message.arenamod.escaped_arena_game_over").formatted(Formatting.RED), false);
                GameSaveManager.getInstance().deleteSave(player.getServer(), player.getUuid());
                endGame(player, game);
            }
        }
    }

    private void awardAllPvpPlayersExcept(MinecraftServer server, GameInstance game, UUID excludeUuid, int expAmount) {
        for (UUID uid : game.getPvpPlayers()) {
            if (uid.equals(excludeUuid)) continue;
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uid);
            if (p != null) {
                if (expAmount > 0) {
                    p.getInventory().offerOrDrop(new ItemStack(ModItems.EXP_POINT, expAmount));
                }
                game.addExpPoints(expAmount);
                p.sendMessage(Text.translatable("message.arenamod.player_eliminated_award", expAmount).formatted(Formatting.GREEN), false);
            }
        }
    }

    /** PVP时间到处理 */
    private void endPvpByTimeLimit(MinecraftServer server, GameInstance game) {
        if (server == null) return;

        // 统计存活玩家人数
        List<ServerPlayerEntity> alivePlayers = new ArrayList<>();
        for (UUID uid : game.getPvpPlayers()) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uid);
            if (p != null && p.isAlive()) {
                alivePlayers.add(p);
            }
        }

        boolean singleWinner = (alivePlayers.size() == 1);

        // 处理所有参与者（存活玩家 + 观众）
        List<UUID> allParticipants = game.getAllPvpParticipants();
        for (UUID uid : allParticipants) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uid);
            if (p != null) {
                if (alivePlayers.contains(p)) {
                    var config = ArenaConfig.getInstance();
                    if (singleWinner) {
                        // Only one remains → victory
                        int expAmount = config.getPvpWinnerExp();
                        int trophies = config.getPvpWinnerTrophy();
                        for (int t = 0; t < trophies; t++) game.addTrophy();
                        if (expAmount > 0) {
                            p.getInventory().offerOrDrop(new ItemStack(ModItems.EXP_POINT, expAmount));
                        }
                        game.addExpPoints(expAmount);
                        p.sendMessage(Text.translatable("message.arenamod.pvp_time_up_winner", expAmount)
                                .formatted(Formatting.GOLD), false);
                    } else {
                        // Multiple survivors → draw
                        int expAmount = config.getPvpDrawExp();
                        if (expAmount > 0) {
                            p.getInventory().offerOrDrop(new ItemStack(ModItems.EXP_POINT, expAmount));
                        }
                        game.addExpPoints(expAmount);
                        p.sendMessage(Text.translatable("message.arenamod.pvp_time_up_draw", expAmount)
                                .formatted(Formatting.YELLOW), false);
                    }
                } else {
                    p.sendMessage(Text.translatable("message.arenamod.pvp_time_up_eliminated").formatted(Formatting.RED), false);
                }
                clearExpItems(p);
                p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
                spawnReturnChestAndRestore(p, game);
                teleportToOverworld(p, game);
                activeGames.remove(uid);
                game.getPvpPlayers().remove(uid);
                game.getPvpSpectators().remove(uid);
                cancelPlayerTasks(uid);
            } else {
                activeGames.remove(uid);
                game.getPvpPlayers().remove(uid);
                game.getPvpSpectators().remove(uid);
            }
        }
        game.setState(GameState.DEFEAT);
    }

    private void checkPvpWinner(MinecraftServer server, GameInstance game) {
        if (game.getPvpPlayers().size() <= 1) {
            var config = ArenaConfig.getInstance();
            int expAmount = config.getPvpWinnerExp();
            int trophies = config.getPvpWinnerTrophy();
            for (UUID uid : game.getPvpPlayers()) {
                ServerPlayerEntity winner = server.getPlayerManager().getPlayer(uid);
                if (winner != null) {
                    for (int t = 0; t < trophies; t++) game.addTrophy();
                    if (expAmount > 0) {
                        winner.getInventory().offerOrDrop(new ItemStack(ModItems.EXP_POINT, expAmount));
                    }
                    game.addExpPoints(expAmount);
                    winner.sendMessage(Text.translatable("message.arenamod.pvp_victory", expAmount, game.getTrophyCount())
                            .formatted(Formatting.GOLD), false);
                }
            }
            endPvpGame(server, game);
        }
    }

    /** PVP游戏结束：为所有参与者归还物品并传送回主世界 */
    private void endPvpGame(MinecraftServer server, GameInstance game) {
        for (UUID uid : game.getAllPvpParticipants()) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uid);
            if (p != null) {
                clearExpItems(p);
                p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
                spawnReturnChestAndRestore(p, game);
                teleportToOverworld(p, game);
                activeGames.remove(uid);
                cancelPlayerTasks(uid);
            } else {
                activeGames.remove(uid);
            }
            game.getPvpPlayers().remove(uid);
            game.getPvpSpectators().remove(uid);
        }
        GameSaveManager.getInstance().addTrophyCount(server, game.getPlayerUuid(), game.getTrophyCount());
        game.setState(GameState.DEFEAT);
        // PVP游戏结束：清理竞技场和玩家放置的方块
        if (server != null) {
            ServerWorld arenaWorld = server.getWorld(ModDimensions.ARENA_WORLD_KEY);
            if (arenaWorld != null) {
                ArenaBuilder.clearPvpArena(arenaWorld, game.getArenaCenter(), game.getArenaHalf(), game.getArenaHeight());
            }
        }
    }

    /** 保存玩家进入竞技场前的原始位置 */
    private void savePlayerPosition(ServerPlayerEntity player) {
        savedPositions.put(player.getUuid(), new SavedPosition(
                player.getPos(),
                player.getYaw(),
                player.getPitch(),
                player.getWorld().getRegistryKey()
        ));
    }

    /** 取出并移除缓存的位置 */
    private SavedPosition takePlayerPosition(UUID playerUuid) {
        return savedPositions.remove(playerUuid);
    }

    private void teleportToOverworld(ServerPlayerEntity player, GameInstance game) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            SavedPosition saved = takePlayerPosition(player.getUuid());
            Vec3d targetPos = new Vec3d(0, 64, 0);
            float yaw = 0;
            float pitch = 0;
            ServerWorld targetWorld = server.getWorld(net.minecraft.world.World.OVERWORLD);

            if (saved != null) {
                targetPos = saved.pos;
                yaw = saved.yaw;
                pitch = saved.pitch;
                if (saved.dimension != null) {
                    ServerWorld savedWorld = server.getWorld(saved.dimension);
                    if (savedWorld != null) {
                        targetWorld = savedWorld;
                    }
                }
            }
            if (targetWorld != null) {
                TeleportTarget target = new TeleportTarget(targetPos, Vec3d.ZERO, yaw, pitch);
                FabricDimensions.teleport(player, targetWorld, target);
            }
        }
    }

    /** 玩家进入竞技场前的位置快照 */
    private record SavedPosition(Vec3d pos, float yaw, float pitch, RegistryKey<World> dimension) {}

    // ===== 结束游戏 =====

    public void endGame(ServerPlayerEntity player, GameInstance game) {
        activeGames.remove(player.getUuid());
        cancelPlayerTasks(player.getUuid());
        game.setState(GameState.DEFEAT);

        MinecraftServer server = player.getServer();

        if (server != null && game.getGameMode() == GameMode.PVP) {
            GameSaveManager.getInstance().addTrophyCount(server, player.getUuid(), game.getTrophyCount());
        }

        if (server != null && game.getGameMode() == GameMode.SINGLE_PLAYER) {
            ServerWorld arenaWorld = server.getWorld(ModDimensions.ARENA_WORLD_KEY);
            if (arenaWorld != null) {
                ArenaBuilder.removeAllMerchants(arenaWorld, game);
                ArenaBuilder.clearArena(arenaWorld, game.getArenaCenter(), game.getArenaHalf(), game.getArenaHeight());
            }
        }

        // 清理 EXP 追踪（无活跃游戏时全量清理）
        if (activeGames.isEmpty()) {
            WaveSpawner.clearAllTracking();
        }

        clearExpItems(player);

        List<ItemStack> protectedItems = new ArrayList<>();
        if (game.getGameMode() == GameMode.SINGLE_PLAYER) {
            List<Integer> passSlots = new ArrayList<>();
            for (int i = 0; i < player.getInventory().size(); i++) {
                ItemStack stack = player.getInventory().getStack(i);
                if (!stack.isEmpty() && MerchantData.isPassItem(stack)) {
                    passSlots.add(i);
                }
            }
            for (int slot : passSlots) {
                player.getInventory().setStack(slot, ItemStack.EMPTY);
            }
            int passesAvailable = game.getPassCount();
            for (int i = 0; i < player.getInventory().size() && passesAvailable > 0; i++) {
                ItemStack stack = player.getInventory().getStack(i);
                if (!stack.isEmpty()) {
                    protectedItems.add(stack.copy());
                    player.getInventory().setStack(i, ItemStack.EMPTY);
                    passesAvailable--;
                }
            }
        }

        if (server != null) {
            teleportToOverworld(player, game);
        }

        spawnReturnChestAndRestore(player, game);

        for (ItemStack stack : protectedItems) {
            player.getInventory().offerOrDrop(stack);
        }
    }

    // ===== 死亡/断线 =====

    public void onPlayerDeath(ServerPlayerEntity player) {
        GameInstance game = activeGames.get(player.getUuid());
        if (game != null) {
            if (game.getGameMode() == GameMode.SINGLE_PLAYER) {
                game.deductLife();
                if (game.getLives() > 0) {
                    // 根据配置决定是否清除背包（keepInventoryOnDeath = true 时保留物品）
                    if (!ArenaConfig.getInstance().isKeepInventoryOnDeath()) {
                        player.getInventory().clear();
                    }
                    clearExpItems(player);
                    BlockPos center = game.getArenaCenter();
                    TeleportTarget target = new TeleportTarget(
                            new Vec3d(center.getX() + 0.5, center.getY() + 1, center.getZ() + 0.5),
                            Vec3d.ZERO, 0, 0
                    );
                    MinecraftServer server = player.getServer();
                    if (server != null) {
                        ServerWorld arenaWorld = server.getWorld(ModDimensions.ARENA_WORLD_KEY);
                        if (arenaWorld != null) {
                            FabricDimensions.teleport(player, arenaWorld, target);
                        }
                    }
                    applyArenaEffects(player);
                    player.sendMessage(Text.translatable("message.arenamod.items_lost_lives_left", game.getLives())
                            .formatted(Formatting.RED), false);
                } else {
                    player.sendMessage(Text.translatable("message.arenamod.game_over_at_wave", game.getCurrentWaveIndex() + 1)
                            .formatted(Formatting.RED), false);
                    GameSaveManager.getInstance().deleteSave(player.getServer(), player.getUuid());
                    endGame(player, game);
                }
            } else if (game.getGameMode() == GameMode.PVP) {
                // 防止重复处理（已在观战模式中）
                if (game.isSpectator(player.getUuid())) return;

                // 死亡掉落由Minecraft自动处理，不清除物品
                // 不生成归还箱子（游戏结束时统一归还）
                game.getPvpPlayers().remove(player.getUuid());
                game.addSpectator(player.getUuid());
                cancelPlayerTasks(player.getUuid());

                // 设置观战模式并传送回竞技场中心
                player.changeGameMode(net.minecraft.world.GameMode.SPECTATOR);
                MinecraftServer server = player.getServer();
                if (server != null) {
                    ServerWorld arenaWorld = server.getWorld(ModDimensions.ARENA_WORLD_KEY);
                    if (arenaWorld != null) {
                        BlockPos center = game.getArenaCenter();
                        TeleportTarget target = new TeleportTarget(
                                new Vec3d(center.getX() + 0.5, center.getY() + 1, center.getZ() + 0.5),
                                Vec3d.ZERO, 0, 0
                        );
                        FabricDimensions.teleport(player, arenaWorld, target);
                    }
                    awardAllPvpPlayersExcept(server, game, player.getUuid(), 20);
                    checkPvpWinner(server, game);
                }
            }
        }
    }

    public void onPlayerDisconnect(ServerPlayerEntity player) {
        GameInstance game = activeGames.remove(player.getUuid());
        if (game != null) {
            if (game.getGameMode() == GameMode.PVP) {
                game.getPvpPlayers().remove(player.getUuid());
                game.getPvpSpectators().remove(player.getUuid());
                MinecraftServer server = player.getServer();
                if (server != null) {
                    awardAllPvpPlayersExcept(server, game, player.getUuid(), 20);
                    checkPvpWinner(server, game);
                }
            } else {
                if (player.getServer() != null) {
                    GameSaveManager.getInstance().saveGame(player.getServer(), game);
                }
            }
        }

        // 如果玩家断开连接时位于竞技场维度，确保传送回主世界
        // 防止玩家数据被保存到竞技场维度，导致下次进入时出生在竞技场
        if (player.getWorld().getRegistryKey().equals(ModDimensions.ARENA_WORLD_KEY)) {
            MinecraftServer server = player.getServer();
            if (server != null) {
                SavedPosition saved = savedPositions.remove(player.getUuid());
                Vec3d targetPos = new Vec3d(0, 64, 0);
                float yaw = 0;
                float pitch = 0;
                ServerWorld targetWorld = server.getWorld(net.minecraft.world.World.OVERWORLD);
                if (saved != null) {
                    targetPos = saved.pos;
                    yaw = saved.yaw;
                    pitch = saved.pitch;
                    if (saved.dimension != null) {
                        ServerWorld savedWorld = server.getWorld(saved.dimension);
                        if (savedWorld != null) {
                            targetWorld = savedWorld;
                        }
                    }
                }
                if (targetWorld != null) {
                    player.teleport(targetWorld, targetPos.x, targetPos.y, targetPos.z, yaw, pitch);
                }
            }
        }

        MatchmakingManager.getInstance().removeFromQueue(player);
        cancelPlayerTasks(player.getUuid());
    }

    public boolean isPlayerInGame(ServerPlayerEntity player) {
        return activeGames.containsKey(player.getUuid());
    }

    public GameInstance getGame(ServerPlayerEntity player) {
        return activeGames.get(player.getUuid());
    }

    /** 获取所有活跃游戏（供作弊接口等外部调用） */
    public Map<UUID, GameInstance> getAllActiveGames() {
        return Collections.unmodifiableMap(activeGames);
    }
}
