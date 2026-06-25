package com.qidai.arenamod.client;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 客户端音乐控制指令
 * /arena music next|previous|loop|order|random
 */
public class ArenaMusicCommands {

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("arena")
                    .then(ClientCommandManager.literal("music")
                            .then(ClientCommandManager.literal("next")
                                    .executes(ctx -> executeNext(ctx)))
                            .then(ClientCommandManager.literal("previous")
                                    .executes(ctx -> executePrevious(ctx)))
                            .then(ClientCommandManager.literal("loop")
                                    .executes(ctx -> executeLoop(ctx)))
                            .then(ClientCommandManager.literal("order")
                                    .executes(ctx -> executeOrder(ctx)))
                            .then(ClientCommandManager.literal("random")
                                    .executes(ctx -> executeRandom(ctx)))
                            .executes(ctx -> executeStatus(ctx))
                    )
            );
        });
    }

    private static int executeNext(CommandContext<FabricClientCommandSource> ctx) {
        ArenaMusicManager.nextTrack();
        ctx.getSource().sendFeedback(Text.translatable("music.arenamod.next", ArenaMusicManager.getCurrentTrackName())
                .formatted(Formatting.GREEN));
        return 1;
    }

    private static int executePrevious(CommandContext<FabricClientCommandSource> ctx) {
        ArenaMusicManager.previousTrack();
        ctx.getSource().sendFeedback(Text.translatable("music.arenamod.previous", ArenaMusicManager.getCurrentTrackName())
                .formatted(Formatting.GREEN));
        return 1;
    }

    private static int executeLoop(CommandContext<FabricClientCommandSource> ctx) {
        ArenaMusicManager.setPlayMode(ArenaMusicManager.PlayMode.LOOP);
        ctx.getSource().sendFeedback(Text.translatable("music.arenamod.mode_loop").formatted(Formatting.AQUA));
        ctx.getSource().sendFeedback(Text.translatable("music.arenamod.current", ArenaMusicManager.getCurrentTrackName())
                .formatted(Formatting.GRAY));
        return 1;
    }

    private static int executeOrder(CommandContext<FabricClientCommandSource> ctx) {
        ArenaMusicManager.setPlayMode(ArenaMusicManager.PlayMode.ORDER);
        ctx.getSource().sendFeedback(Text.translatable("music.arenamod.mode_order").formatted(Formatting.AQUA));
        ctx.getSource().sendFeedback(Text.translatable("music.arenamod.current", ArenaMusicManager.getCurrentTrackName())
                .formatted(Formatting.GRAY));
        return 1;
    }

    private static int executeRandom(CommandContext<FabricClientCommandSource> ctx) {
        ArenaMusicManager.setPlayMode(ArenaMusicManager.PlayMode.RANDOM);
        ctx.getSource().sendFeedback(Text.translatable("music.arenamod.mode_random").formatted(Formatting.AQUA));
        ctx.getSource().sendFeedback(Text.translatable("music.arenamod.current", ArenaMusicManager.getCurrentTrackName())
                .formatted(Formatting.GRAY));
        return 1;
    }

    private static int executeStatus(CommandContext<FabricClientCommandSource> ctx) {
        if (ArenaMusicManager.getMusicCount() == 0) {
            ctx.getSource().sendFeedback(Text.translatable("music.arenamod.no_files").formatted(Formatting.RED));
            ctx.getSource().sendFeedback(Text.translatable("music.arenamod.hint").formatted(Formatting.GRAY));
        }

        String modeName = switch (ArenaMusicManager.getPlayMode()) {
            case LOOP -> Text.translatable("music.arenamod.mode_loop").getString();
            case ORDER -> Text.translatable("music.arenamod.mode_order").getString();
            case RANDOM -> Text.translatable("music.arenamod.mode_random").getString();
        };

        ctx.getSource().sendFeedback(Text.translatable("music.arenamod.status_title").formatted(Formatting.GOLD));
        ctx.getSource().sendFeedback(Text.translatable("music.arenamod.playing", (ArenaMusicManager.isPlaying() ?
                Text.translatable("options.on") : Text.translatable("options.off")).getString())
                .formatted(Formatting.WHITE));
        ctx.getSource().sendFeedback(Text.translatable("music.arenamod.mode", modeName)
                .formatted(Formatting.AQUA));
        if (ArenaMusicManager.getMusicCount() > 0) {
            ctx.getSource().sendFeedback(Text.translatable("music.arenamod.current", ArenaMusicManager.getCurrentTrackName())
                    .formatted(Formatting.YELLOW));
        }
        ctx.getSource().sendFeedback(Text.translatable("music.arenamod.tracks", ArenaMusicManager.getMusicCount())
                .formatted(Formatting.GRAY));
        if (!ArenaMusicManager.isAudioSystemAvailable()) {
            ctx.getSource().sendFeedback(Text.translatable("music.arenamod.hint")
                    .formatted(Formatting.GRAY));
        }
        return 1;
    }
}
