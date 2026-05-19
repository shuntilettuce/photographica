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

        int x = this.x;
        int y = this.y;

        // 現像 button
        this.addDrawableChild(ButtonWidget.builder(
                Text.literal("現像"),
                b -> this.client.interactionManager.clickButton(this.handler.syncId, 0)
        ).dimensions(x + 60, y + 58, 56, 16).build());
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
        context.drawText(this.textRenderer, Text.literal("フィルム1"), x + 18,  y + 24, 0x404040, false);
        context.drawText(this.textRenderer, Text.literal("フィルム2"), x + 54,  y + 24, 0x404040, false);
        context.drawText(this.textRenderer, Text.literal("フィルム3"), x + 90,  y + 24, 0x404040, false);
        context.drawText(this.textRenderer, Text.literal("現像液"),    x + 128, y + 24, 0x404040, false);
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        context.drawText(this.textRenderer, this.title, this.titleX, this.titleY, 0x404040, false);
    }
}
