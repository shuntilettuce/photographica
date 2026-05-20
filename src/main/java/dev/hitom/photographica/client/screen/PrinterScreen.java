package dev.hitom.photographica.client.screen;

import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.component.SdCardData;
import dev.hitom.photographica.screen.PrinterScreenHandler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public class PrinterScreen extends HandledScreen<PrinterScreenHandler> {

    public PrinterScreen(PrinterScreenHandler handler, PlayerInventory playerInventory, Text title) {
        super(handler, playerInventory, title);
        this.backgroundWidth = 176;
        this.backgroundHeight = 172;
    }

    @Override
    protected void init() {
        super.init();
        this.titleX = (this.backgroundWidth - this.textRenderer.getWidth(this.title)) / 2;
        int x = this.x, y = this.y;
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("全プリント"),
                b -> this.client.interactionManager.clickButton(this.handler.syncId, 0)
        ).dimensions(x + 7, y + 58, 80, 16).build());
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("1枚プリント"),
                b -> this.client.interactionManager.clickButton(this.handler.syncId, 1)
        ).dimensions(x + 94, y + 58, 75, 16).build());
    }

    @Override
    protected void drawBackground(DrawContext ctx, float delta, int mouseX, int mouseY) {
        int x = this.x, y = this.y, w = backgroundWidth, h = backgroundHeight;
        GuiHelper.drawPanel(ctx, x, y, w, h);
        GuiHelper.drawSeparator(ctx, x + 7, y + 76, w - 14);
        GuiHelper.drawSlotBox(ctx, x + 44, y + 35);
        GuiHelper.drawSlotBox(ctx, x + 80, y + 35);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground(ctx, mouseX, mouseY, delta);
        super.render(ctx, mouseX, mouseY, delta);
        this.drawMouseoverTooltip(ctx, mouseX, mouseY);
    }

    @Override
    protected void drawForeground(DrawContext ctx, int mouseX, int mouseY) {
        ctx.drawText(this.textRenderer, this.title, this.titleX, this.titleY, GuiHelper.TEXT_DARK, false);
        ctx.drawText(this.textRenderer, this.playerInventoryTitle, this.playerInventoryTitleX, this.playerInventoryTitleY, GuiHelper.TEXT_DARK, false);
        drawCentered(ctx, "SDカード", 44, 25);
        drawCentered(ctx, "印画紙",   80, 25);

        ItemStack sd = this.handler.getSlot(0).getStack();
        if (!sd.isEmpty() && sd.contains(ModDataComponents.SD_CARD)) {
            SdCardData data = sd.getOrDefault(ModDataComponents.SD_CARD, SdCardData.EMPTY);
            if (!data.isEmpty()) {
                String count = data.photos().size() + "枚";
                ctx.drawText(this.textRenderer, Text.literal("§e" + count),
                        44 + 8 - this.textRenderer.getWidth(count) / 2, 35, 0xFFFFFF, false);
            }
        }
    }

    private void drawCentered(DrawContext ctx, String text, int slotX, int y) {
        int tx = slotX + 8 - this.textRenderer.getWidth(text) / 2;
        ctx.drawText(this.textRenderer, Text.literal(text), tx, y, GuiHelper.TEXT_DARK, false);
    }
}
