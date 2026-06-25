package com.qidai.arenamod.game;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.ItemStackArgument;
import net.minecraft.command.argument.ItemStackArgumentType;
import net.minecraft.item.ItemStack;
import com.qidai.arenamod.item.ModItems;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.Collection;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class ModCommands {

    public static void register() {
        CommandRegistrationCallback.EVENT.register(ModCommands::registerCommands);
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher,
                                          CommandRegistryAccess registryAccess,
                                          CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(literal("arena")
                .then(literal("resume")
                        .executes(ModCommands::resumeGame))
                .then(literal("new")
                        .executes(ModCommands::newGame))
                .then(literal("trophy")
                        .then(literal("exchange")
                                .then(argument("item", ItemStackArgumentType.itemStack(registryAccess))
                                        .then(argument("amount", IntegerArgumentType.integer(0, 128))
                                                .executes(ModCommands::exchangeTrophy)))))
                .then(literal("clearblocks")
                        .executes(ModCommands::clearPlayerBlocks))
                .then(literal("individual")
                        .then(argument("key", StringArgumentType.word())
                                .executes(ModCommands::spawnMerchants)))
                .then(literal("life")
                        .executes(ModCommands::buyLife))
                // 管理员指令（需要 OP）
                .then(literal("admin")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(literal("setexp")
                                .then(argument("targets", EntityArgumentType.players())
                                        .then(argument("amount", IntegerArgumentType.integer(0, 99999))
                                                .executes(ModCommands::adminSetExp))))
                        .then(literal("settrophy")
                                .then(argument("targets", EntityArgumentType.players())
                                        .then(argument("amount", IntegerArgumentType.integer(0, 99999))
                                                .executes(ModCommands::adminSetTrophy)))))

        );
    }

    private static int resumeGame(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.translatable("message.arenamod.player_only_command"));
            return 0;
        }

        GameSaveManager.GameSaveData saveData = GameSaveManager.getInstance().loadGame(
                player.getServer(), player.getUuid());
        if (saveData == null) {
            player.sendMessage(Text.translatable("message.arenamod.save_not_found").formatted(Formatting.RED), false);
            return 0;
        }

        GameManager.getInstance().resumeGame(player, saveData);
        return 1;
    }

    private static int newGame(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.translatable("message.arenamod.player_only_command"));
            return 0;
        }

        GameSaveManager.getInstance().deleteSave(player.getServer(), player.getUuid());
        player.sendMessage(Text.translatable("message.arenamod.save_deleted").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int exchangeTrophy(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.translatable("message.arenamod.player_only_command"));
            return 0;
        }

        ItemStackArgument itemArg = ItemStackArgumentType.getItemStackArgument(ctx, "item");
        int amount = IntegerArgumentType.getInteger(ctx, "amount");

        // 检查奖杯数
        int trophyCount = GameSaveManager.getInstance().loadTrophyCount(player.getServer(), player.getUuid());
        if (trophyCount < 1) {
            player.sendMessage(Text.translatable("message.arenamod.trophies_not_enough").formatted(Formatting.RED), false);
            return 0;
        }

        // 扣除 1 奖杯
        GameSaveManager.getInstance().saveTrophyCount(player.getServer(), player.getUuid(), trophyCount - 1);

        // 发放物品（拆分为多个符合堆叠上限的 ItemStack）
        String itemName = Text.translatable("message.arenamod.unknown_item").getString();
        try {
            if (amount > 0) {
                ItemStack single = itemArg.createStack(1, false);
                itemName = single.getItem().getName().getString();
                int maxStack = single.getMaxCount();
                int remaining = amount;
                while (remaining > 0) {
                    int batchSize = Math.min(remaining, maxStack);
                    player.getInventory().offerOrDrop(itemArg.createStack(batchSize, false));
                    remaining -= batchSize;
                }
            } else {
                ItemStack single = itemArg.createStack(1, false);
                itemName = single.getItem().getName().getString();
            }
        } catch (CommandSyntaxException e) {
            player.sendMessage(Text.translatable("message.arenamod.item_data_invalid").formatted(Formatting.RED), false);
            return 0;
        }

        player.sendMessage(Text.translatable("message.arenamod.trophy_exchange", amount, itemName)
                .formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int clearPlayerBlocks(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.translatable("message.arenamod.player_only_command"));
            return 0;
        }

        GameInstance game = GameManager.getInstance().getGame(player);
        if (game == null) {
            player.sendMessage(Text.translatable("message.arenamod.not_in_game").formatted(Formatting.RED), false);
            return 0;
        }

        if (!(player.getWorld() instanceof ServerWorld serverWorld)) {
            player.sendMessage(Text.translatable("message.arenamod.server_world_only").formatted(Formatting.RED), false);
            return 0;
        }

        // 清除竞技场内所有非结构性方块
        ArenaBuilder.clearNonStructuralBlocks(serverWorld, game.getArenaCenter(),
                game.getArenaHalf(), game.getArenaHeight());

        // 如果是PVP模式，同时清空放置记录
        if (game.isPVP()) {
            game.getPlacedBlocks().clear();
        }

        player.sendMessage(Text.translatable("message.arenamod.blocks_cleared").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int spawnMerchants(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.translatable("message.arenamod.player_only_command"));
            return 0;
        }

        String key = StringArgumentType.getString(ctx, "key");
        if (!"114514".equals(key)) {
            player.sendMessage(Text.translatable("message.arenamod.invalid_key").formatted(Formatting.RED), false);
            return 0;
        }

        GameManager.getInstance().spawnPlayerMerchants(player);
        return 1;
    }

    private static int buyLife(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.translatable("message.arenamod.player_only_command"));
            return 0;
        }

        GameInstance game = GameManager.getInstance().getGame(player);
        if (game == null || game.getGameMode() != GameMode.SINGLE_PLAYER) {
            player.sendMessage(Text.translatable("message.arenamod.not_in_single_player").formatted(Formatting.RED), false);
            return 0;
        }

        // 统计经验点
        int expCount = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isOf(ModItems.EXP_POINT)) {
                expCount += stack.getCount();
            }
        }

        if (expCount < 80) {
            player.sendMessage(Text.translatable("message.arenamod.exp_not_enough_life").formatted(Formatting.RED), false);
            return 0;
        }

        // 扣除80经验
        int toRemove = 80;
        for (int i = 0; i < player.getInventory().size() && toRemove > 0; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isOf(ModItems.EXP_POINT)) {
                int removed = Math.min(toRemove, stack.getCount());
                stack.decrement(removed);
                toRemove -= removed;
            }
        }
        game.addExpPoints(-80);

        // 加一条命
        game.addLife();

        player.sendMessage(Text.translatable("message.arenamod.life_exchanged", game.getLives())
                .formatted(Formatting.GREEN), false);
        return 1;
    }

    // ===== 管理员指令 =====

    /** /arena admin setexp <targets> <amount> - 设置玩家经验点数量 */
    private static int adminSetExp(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        Collection<ServerPlayerEntity> targets = EntityArgumentType.getPlayers(ctx, "targets");
        int amount = IntegerArgumentType.getInteger(ctx, "amount");

        ItemStack expStack = new ItemStack(ModItems.EXP_POINT, amount);
        for (ServerPlayerEntity target : targets) {
            // 清空原有经验点
            for (int i = 0; i < target.getInventory().size(); i++) {
                ItemStack stack = target.getInventory().getStack(i);
                if (stack.isOf(ModItems.EXP_POINT)) {
                    target.getInventory().setStack(i, ItemStack.EMPTY);
                }
            }
            // 发放新经验点
            target.getInventory().offerOrDrop(expStack.copy());
            target.sendMessage(Text.translatable("message.arenamod.admin_setexp", amount).formatted(Formatting.GREEN), false);
        }
        return 1;
    }

    /** /arena admin settrophy <targets> <amount> - 设置玩家奖杯数量 */
    private static int adminSetTrophy(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        Collection<ServerPlayerEntity> targets = EntityArgumentType.getPlayers(ctx, "targets");
        int amount = IntegerArgumentType.getInteger(ctx, "amount");

        for (ServerPlayerEntity target : targets) {
            GameSaveManager.getInstance().saveTrophyCount(target.getServer(), target.getUuid(), amount);
            target.sendMessage(Text.translatable("message.arenamod.admin_settrophy", amount).formatted(Formatting.GREEN), false);
        }
        return 1;
    }
}
