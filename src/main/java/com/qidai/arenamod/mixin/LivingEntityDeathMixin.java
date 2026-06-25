package com.qidai.arenamod.mixin;

import com.qidai.arenamod.config.ArenaConfig;
import com.qidai.arenamod.dimension.ModDimensions;
import com.qidai.arenamod.game.GameInstance;
import com.qidai.arenamod.game.GameManager;
import com.qidai.arenamod.item.ModItems;
import com.qidai.arenamod.wave.WaveSpawner;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Entity death handler:
 * - Monster killed → grants EXP points per tracking table
 * - PvP kill → grants EXP + trophy (values from config)
 */
@Mixin(LivingEntity.class)
public class LivingEntityDeathMixin {

    @Inject(at = @At("HEAD"), method = "onDeath")
    private void onDeath(DamageSource source, CallbackInfo ci) {
        LivingEntity entity = (LivingEntity) (Object) this;

        // 只在服务端竞技场维度中处理
        if (entity.getWorld().isClient) return;
        if (!entity.getWorld().getRegistryKey().equals(ModDimensions.ARENA_WORLD_KEY)) return;

        // 获取击杀者
        if (!(source.getAttacker() instanceof ServerPlayerEntity killer)) return;

        // ===== PvP kill: player kills another player =====
        if (entity instanceof ServerPlayerEntity killedPlayer) {
            GameInstance game = GameManager.getInstance().getGame(killer);
            if (game != null && game.isPVP()) {
                int expOnKill = ArenaConfig.getInstance().getPvpExpOnKill();
                int trophyOnKill = ArenaConfig.getInstance().getPvpTrophyOnKill();
                if (expOnKill > 0) {
                    killer.getInventory().offerOrDrop(new ItemStack(ModItems.EXP_POINT, expOnKill));
                }
                game.addExpPoints(expOnKill);
                for (int t = 0; t < trophyOnKill; t++) game.addTrophy();

                killer.sendMessage(Text.translatable("message.arenamod.pvp_kill", expOnKill, trophyOnKill, game.getTrophyCount())
                        .formatted(Formatting.GOLD), false);
            }
            return;
        }

        // ===== 情况2: 击杀怪物 (单人/PVP通用) =====
        int expValue = WaveSpawner.getExpValue(entity.getUuid());
        if (expValue <= 0) return;

        // 给予经验点物品（直接进入背包）
        if (expValue > 0) {
            killer.getInventory().offerOrDrop(new ItemStack(ModItems.EXP_POINT, expValue));
        }

        // 更新游戏实例的 EXP 统计和杀敌数
        GameInstance game = GameManager.getInstance().getGame(killer);
        if (game != null) {
            game.addExpPoints(expValue);
            game.addKill();
        }

        // 清除 EXP 追踪
        WaveSpawner.removeTracking(entity.getUuid());
    }
}
