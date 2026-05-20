package dev.hitom.photographica.client.screen;

import dev.hitom.photographica.screen.DarkroomScreenHandler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public class DarkroomScreen extends HandledScreen<DarkroomScreenHandler> {

    public DarkroomScreen(DarkroomScreenHandler handler, PlayerInventory playerInventory, Text title) {
        super(handler, playerInventory, title);
        this.backgroundWidth  = 176;
        this.backgroundHeight = 186;
    }

    @Override
    protected void init() {
        super.init();
        this.playerInventoryTitleY = 94;
        int x = this.x, y = this.y;
        addDrawableChild(SafelightButton.of(x + 6, y + 72, 80, Text.literal("DEVELOP"),
                b -> this.client.interactionManager.clickButton(this.handler.syncId, 0)));
        addDrawableChild(SafelightButton.primary(x + 90, y + 72, 80, Text.literal("CAM → DEV"),
                b -> this.client.interactionManager.clickButton(this.handler.syncId, 1)));
    }

    @Override
    protected void drawBackground(DrawContext ctx, float delta, int mouseX, int mouseY) {
        int x = this.x, y = this.y, w = backgroundWidth, h = backgroundHeight;

        GuiHelper.drawPanel(ctx, x, y, w, h);

        // Horizontal rule at y=14
        GuiHelper.drawRule(ctx, x + 6, y + 14, w - 12);

        // LCD background top-right
        GuiHelper.drawLcd(ctx, x + w - 42, y + 3, 36, 9);

        // Well (engraved trough) containing the 4 film/chem slots
        GuiHelper.drawWell(ctx, x + 22, y + 22, 144, 22);

        // Film slots (normal brass)
        GuiHelper.drawSlot(ctx, x + 26, y + 26);
        GuiHelper.drawSlot(ctx, x + 62, y + 26);
        GuiHelper.drawSlot(ctx, x + 98, y + 26);

        // Chem slot (ember variant)
        GuiHelper.drawSlotEmber(ctx, x + 134, y + 26);

        // Camera slot — HOT if camera loaded, else normal
        boolean cameraLoaded = this.handler.getSlot(4).hasStack();
        if (cameraLoaded) {
            GuiHelper.drawSlotHot(ctx, x + 80, y + 52);
        } else {
            GuiHelper.drawSlot(ctx, x + 80, y + 52);
        }

        // Rule above buttons
        GuiHelper.drawRule(ctx, x + 6, y + 70, w - 12);

        // Nameplate
        GuiHelper.drawNameplate(ctx, x + 6, y + 93, 164);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground(ctx, mouseX, mouseY, delta);
        super.render(ctx, mouseX, mouseY, delta);
        this.drawMouseoverTooltip(ctx, mouseX, mouseY);
    }

    @Override
    protected void drawForeground(DrawContext ctx, int mouseX, int mouseY) {
        // Pip + title
        ctx.fill(3, 5, 6, 8, GuiHelper.SAFELIGHT);
        ctx.drawText(textRenderer, Text.literal("PORTABLE DARKROOM"), 9, 5, GuiHelper.CREAM, false);

        // LCD text "SAFE"
        ctx.drawText(textRenderer, Text.literal("SAFE"),
                backgroundWidth - 41, 4, GuiHelper.SAFELIGHT, false);

        // Slot labels
        ctx.drawText(textRenderer, Text.literal("FILM"),  43, 45, GuiHelper.CREAM_DIM, false);
        ctx.drawText(textRenderer, Text.literal("CHEM"), 132, 45, GuiHelper.EMBER,     false);

        // Camera slot labels
        ctx.drawText(textRenderer, Text.literal("CAMERA"), 100, 54, GuiHelper.BRASS_BRIGHT, false);
        ctx.drawText(textRenderer, Text.literal("IN"),       50, 54, GuiHelper.CREAM_DIM,   false);

        // Camera loaded pip + status text
        boolean cameraLoaded = this.handler.getSlot(4).hasStack();
        ctx.fill(72, 58, 75, 61, cameraLoaded ? GuiHelper.SAFELIGHT : GuiHelper.PIP_OFF);
        if (cameraLoaded) {
            ctx.drawText(textRenderer, Text.literal("LOADED"), 100, 62, GuiHelper.CREAM_DIM, false);
        }

        // Nameplate text
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("DR-1 · 35MM CHEMICAL BATH"),
                backgroundWidth / 2, 95, GuiHelper.FRAME_LO);

        // Player inventory label
        ctx.drawText(textRenderer, Text.literal("INVENTORY"),
                playerInventoryTitleX, playerInventoryTitleY, GuiHelper.CREAM_DIM, false);
    }
}
