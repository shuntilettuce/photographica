package dev.hitom.photographica.client.screen;

import dev.hitom.photographica.screen.CameraStandScreenHandler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public class CameraStandScreen extends HandledScreen<CameraStandScreenHandler> {

    public CameraStandScreen(CameraStandScreenHandler handler, PlayerInventory playerInventory, Text title) {
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
                Text.literal("レンズ装着"),
                b -> this.client.interactionManager.clickButton(this.handler.syncId, 0)
        ).dimensions(x + 7, y + 58, 55, 16).build());
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("装填"),
                b -> this.client.interactionManager.clickButton(this.handler.syncId, 1)
        ).dimensions(x + 65, y + 58, 46, 16).build());
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("取り出し"),
                b -> this.client.interactionManager.clickButton(this.handler.syncId, 2)
        ).dimensions(x + 114, y + 58, 55, 16).build());
    }

    @Override
    protected void drawBackground(DrawContext ctx, float delta, int mouseX, int mouseY) {
        int x = this.x, y = this.y, w = backgroundWidth, h = backgroundHeight;
        GuiHelper.drawPanel(ctx, x, y, w, h);
        GuiHelper.drawSeparator(ctx, x + 7, y + 76, w - 14);
        GuiHelper.drawSlotBox(ctx, x + 35, y + 35);
        GuiHelper.drawSlotBox(ctx, x + 71, y + 35);
        GuiHelper.drawSlotBox(ctx, x + 107, y + 35);
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
        drawCentered(ctx, "カメラ",     35, 25);
        drawCentered(ctx, "レンズ",     71, 25);
        drawCentered(ctx, "フィルム/SD", 107, 25);
    }

    private void drawCentered(DrawContext ctx, String text, int slotX, int y) {
        int tx = slotX + 8 - this.textRenderer.getWidth(text) / 2;
        ctx.drawText(this.textRenderer, Text.literal(text), tx, y, GuiHelper.TEXT_DARK, false);
    }
}
