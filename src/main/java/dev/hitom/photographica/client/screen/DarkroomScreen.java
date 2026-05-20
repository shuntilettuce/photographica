package dev.hitom.photographica.client.screen;

import dev.hitom.photographica.screen.DarkroomScreenHandler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public class DarkroomScreen extends HandledScreen<DarkroomScreenHandler> {

    public DarkroomScreen(DarkroomScreenHandler handler, PlayerInventory playerInventory, Text title) {
        super(handler, playerInventory, title);
        this.backgroundWidth = 176;
        this.backgroundHeight = 186;
    }

    @Override
    protected void init() {
        super.init();
        this.titleX = (this.backgroundWidth - this.textRenderer.getWidth(this.title)) / 2;
        this.playerInventoryTitleY = 94;
        int x = this.x, y = this.y;
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("現像"),
                b -> this.client.interactionManager.clickButton(this.handler.syncId, 0)
        ).dimensions(x + 7, y + 72, 70, 16).build());
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("カメラから取り出し"),
                b -> this.client.interactionManager.clickButton(this.handler.syncId, 1)
        ).dimensions(x + 90, y + 72, 79, 16).build());
    }

    @Override
    protected void drawBackground(DrawContext ctx, float delta, int mouseX, int mouseY) {
        int x = this.x, y = this.y, w = backgroundWidth, h = backgroundHeight;
        GuiHelper.drawPanel(ctx, x, y, w, h);
        GuiHelper.drawSeparator(ctx, x + 7, y + 90, w - 14);
        // Film slots row
        GuiHelper.drawSlotBox(ctx, x + 26, y + 26);
        GuiHelper.drawSlotBox(ctx, x + 62, y + 26);
        GuiHelper.drawSlotBox(ctx, x + 98, y + 26);
        // Developer tank slot
        GuiHelper.drawSlotBox(ctx, x + 134, y + 26);
        // Camera slot
        GuiHelper.drawSlotBox(ctx, x + 80, y + 52);
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
        drawCentered(ctx, "フィルム1",     26, 16);
        drawCentered(ctx, "フィルム2",     62, 16);
        drawCentered(ctx, "フィルム3",     98, 16);
        drawCentered(ctx, "現像液",       134, 16);
        drawCentered(ctx, "フィルムカメラ",  80, 42);
    }

    private void drawCentered(DrawContext ctx, String text, int slotX, int y) {
        int tx = slotX + 8 - this.textRenderer.getWidth(text) / 2;
        ctx.drawText(this.textRenderer, Text.literal(text), tx, y, GuiHelper.TEXT_DARK, false);
    }
}
