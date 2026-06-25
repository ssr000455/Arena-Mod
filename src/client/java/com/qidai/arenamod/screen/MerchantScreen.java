package com.qidai.arenamod.screen;

import com.qidai.arenamod.game.MerchantData;
import com.qidai.arenamod.network.ModClientNetworking;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 商人交易界面（支持物品商人和方块商人）
 */
public class MerchantScreen extends Screen {
    private static final int BUTTON_WIDTH = 160;
    private static final int BUTTON_HEIGHT = 20;
    private static final int START_Y = 40;
    private static final int ROW_HEIGHT = 24;

    private final int tier;
    private final boolean upgradeAvailable;
    private final int merchantType;
    private final List<MerchantData.MerchantItem> items;
    private int scrollOffset = 0;
    private int maxScroll = 0;

    public MerchantScreen(int tier, boolean upgradeAvailable) {
        this(tier, upgradeAvailable, MerchantData.MERCHANT_TYPE_ITEM);
    }

    public MerchantScreen(int tier, boolean upgradeAvailable, int merchantType) {
        super(Text.translatable("screen.arenamod.merchant.title",
                Text.translatable(MerchantData.getMerchantName(merchantType)), tier));
        this.tier = tier;
        this.upgradeAvailable = upgradeAvailable;
        this.merchantType = merchantType;
        this.items = new ArrayList<>(MerchantData.getItemsForMerchant(merchantType, tier));
    }

    @Override
    protected void init() {
        super.init();
        int centerX = this.width / 2;
        int currentY = START_Y;

        String currentCategory = "";
        int index = 0;

        for (MerchantData.MerchantItem item : items) {
            // 分类标题
            if (!item.category().equals(currentCategory)) {
                currentCategory = item.category();
                currentY += 6;
                addDrawableChild(ButtonWidget.builder(
                        Text.translatable("merchant.arenamod.entry",
                                Text.translatable(MerchantData.getCategoryName(currentCategory)))
                                .append(Text.translatable("merchant.arenamod.entry_cost", item.expCost())),
                        button -> {}
                ).dimensions(centerX - 150, currentY, 300, 16).build());
                currentY += 20;
            }

            // 商品按钮
            int finalIndex = index;
            Text itemName = item.item().getName();
            int count = item.item().getCount();
            Text buttonText = count > 1
                    ? Text.literal("").append(itemName).append(Text.literal(" x" + count + " — " + item.expCost() + " EXP"))
                    : Text.literal("").append(itemName).append(Text.literal(" — " + item.expCost() + " EXP"));
            addDrawableChild(ButtonWidget.builder(
                    buttonText,
                    button -> {
                        ModClientNetworking.sendMerchantBuy(finalIndex, merchantType);
                        client.setScreen(null);
                    }
            ).dimensions(centerX - 80, currentY, BUTTON_WIDTH, BUTTON_HEIGHT).build());

            currentY += ROW_HEIGHT;
            index++;
        }

        // 升级按钮
        if (upgradeAvailable) {
            currentY += 10;
            int upgradeCost = MerchantData.getUpgradeCost(tier);
            String targetTier = Text.translatable("merchant.arenamod.tier." + (tier + 1)).getString();
            addDrawableChild(ButtonWidget.builder(
                    Text.translatable("merchant.arenamod.upgrade", targetTier, upgradeCost),
                    button -> {
                        ModClientNetworking.sendMerchantBuy(-1, merchantType);
                        client.setScreen(null);
                    }
            ).dimensions(centerX - 80, currentY, BUTTON_WIDTH, BUTTON_HEIGHT).build());
        }

        maxScroll = Math.max(0, currentY - this.height + 20);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        int oldScroll = scrollOffset;
        scrollOffset = (int) Math.max(0, Math.min(maxScroll, scrollOffset - amount * 20));
        int delta = scrollOffset - oldScroll;
        for (var widget : this.children()) {
            if (widget instanceof net.minecraft.client.gui.widget.ClickableWidget clickable) {
                clickable.setY(clickable.getY() - delta);
            }
        }
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
