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
        // Use a taller background to fit all slots and buttons
        this.backgroundWidth = 176;
        this.backgroundHeight = 186;
    }

    @Override
    protected void init() {
        super.init();
        // Center title
        this.titleX = (this.backgroundWidth - this.textRenderer.getWidth(this.title)) / 2;

        int x = this.x;
        int y = this.y;

        // Action buttons below the three slots
        // レンズ装着 button
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("レンズ装着"),
                b -> this.client.interactionManager.clickButton(this.handler.syncId, 0)
        ).dimensions(x + 7, y + 58, 55, 16).build());

        // 装填 button
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("装填"),
                b -> this.client.interactionManager.clickButton(this.handler.syncId, 1)
        ).dimensions(x + 65, y + 58, 46, 16).build());

        // 取り出し button
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("取り出し"),
                b -> this.client.interactionManager.clickButton(this.handler.syncId, 2)
        ).dimensions(x + 114, y + 58, 55, 16).build());
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int x = this.x;
        int y = this.y;
        // Draw a dark panel as background
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

        // Draw slot labels above each slot
        context.drawText(this.textRenderer, Text.literal("カメラ"),  x + 26,  y + 24, 0x404040, false);
        context.drawText(this.textRenderer, Text.literal("レンズ"), x + 62,  y + 24, 0x404040, false);
        context.drawText(this.textRenderer, Text.literal("フィルム/SD"), x + 92, y + 24, 0x404040, false);
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        // Title at top
        context.drawText(this.textRenderer, this.title, this.titleX, this.titleY, 0x404040, false);
    }
}
