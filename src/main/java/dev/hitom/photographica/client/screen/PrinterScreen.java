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
        this.backgroundHeight = 186;
    }

    @Override
    protected void init() {
        super.init();
        this.titleX = (this.backgroundWidth - this.textRenderer.getWidth(this.title)) / 2;

        int x = this.x;
        int y = this.y;

        // 全プリント button
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("全プリント"),
                b -> this.client.interactionManager.clickButton(this.handler.syncId, 0)
        ).dimensions(x + 20, y + 58, 60, 16).build());

        // 1枚プリント button
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("1枚プリント"),
                b -> this.client.interactionManager.clickButton(this.handler.syncId, 1)
        ).dimensions(x + 95, y + 58, 60, 16).build());
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int x = this.x;
        int y = this.y;
        context.fill(x, y, x + backgroundWidth, y + backgroundHeight, 0xC0000000);
        context.fill(x + 1, y + 1, x + backgroundWidth - 1, y + backgroundHeight - 1, 0xFF8B8B8B);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        this.drawMouseoverTooltip(context, mouseX, mouseY);

        int x = this.x;
        int y = this.y;

        // Slot labels
        context.drawText(this.textRenderer, Text.literal("SDカード"), x + 30, y + 24, 0x404040, false);
        context.drawText(this.textRenderer, Text.literal("印画紙"),   x + 70, y + 24, 0x404040, false);

        // SD card photo count
        ItemStack sdStack = this.handler.getSlot(0).getStack();
        if (!sdStack.isEmpty() && sdStack.contains(ModDataComponents.SD_CARD)) {
            SdCardData data = sdStack.getOrDefault(ModDataComponents.SD_CARD, SdCardData.EMPTY);
            context.drawText(this.textRenderer,
                    Text.literal("§e" + data.photos().size() + "枚"),
                    x + 110, y + 35, 0xFFFFFF, false);
        }
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        context.drawText(this.textRenderer, this.title, this.titleX, this.titleY, 0x404040, false);
    }
}
