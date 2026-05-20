package dev.hitom.photographica.client.screen;

import dev.hitom.photographica.screen.CameraStandScreenHandler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public class CameraStandScreen extends HandledScreen<CameraStandScreenHandler> {

    public CameraStandScreen(CameraStandScreenHandler handler, PlayerInventory playerInventory, Text title) {
        super(handler, playerInventory, title);
        this.backgroundWidth  = 176;
        this.backgroundHeight = 172;
    }

    @Override
    protected void init() {
        super.init();
        int x = this.x, y = this.y;
        addDrawableChild(SafelightButton.of(x + 6, y + 58, 52, Text.literal("MOUNT"),
                b -> this.client.interactionManager.clickButton(this.handler.syncId, 0)));
        addDrawableChild(SafelightButton.primary(x + 62, y + 58, 52, Text.literal("LOAD"),
                b -> this.client.interactionManager.clickButton(this.handler.syncId, 1)));
        addDrawableChild(SafelightButton.ghost(x + 118, y + 58, 52, Text.literal("EJECT"),
                b -> this.client.interactionManager.clickButton(this.handler.syncId, 2)));
    }

    @Override
    protected void drawBackground(DrawContext ctx, float delta, int mouseX, int mouseY) {
        int x = this.x, y = this.y, w = backgroundWidth, h = backgroundHeight;

        // Main panel
        GuiHelper.drawPanel(ctx, x, y, w, h);

        // Horizontal rule at y=14
        GuiHelper.drawRule(ctx, x + 6, y + 14, w - 12);

        // LCD background: w=36, h=9 at top-right
        GuiHelper.drawLcd(ctx, x + w - 42, y + 3, 36, 9);

        // Coupling lines between slots (two-row, BRASS_DIM then BRASS)
        ctx.fill(x + 52, y + 42, x + 70, y + 43, GuiHelper.BRASS_DIM);
        ctx.fill(x + 52, y + 43, x + 70, y + 44, GuiHelper.BRASS);
        ctx.fill(x + 88, y + 42, x + 106, y + 43, GuiHelper.BRASS_DIM);
        ctx.fill(x + 88, y + 43, x + 106, y + 44, GuiHelper.BRASS);

        // Nameplate
        GuiHelper.drawNameplate(ctx, x + 6, y + 80, 164);

        // Slots (absolute screen coords, ix/iy = inner 16×16 top-left)
        GuiHelper.drawSlot(ctx, x + 35, y + 35);
        GuiHelper.drawSlot(ctx, x + 71, y + 35);
        GuiHelper.drawSlot(ctx, x + 107, y + 35);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground(ctx, mouseX, mouseY, delta);
        super.render(ctx, mouseX, mouseY, delta);
        this.drawMouseoverTooltip(ctx, mouseX, mouseY);
    }

    @Override
    protected void drawForeground(DrawContext ctx, int mouseX, int mouseY) {
        // Safelight pip (3×3) at (3,5) then title
        ctx.fill(3, 5, 6, 8, GuiHelper.SAFELIGHT);
        ctx.drawText(textRenderer, Text.literal("CAMERA STAND"), 9, 5, GuiHelper.CREAM, false);

        // LCD text "·READY·"
        ctx.drawText(textRenderer, Text.literal("·READY·"),
                backgroundWidth - 41, 4, GuiHelper.SAFELIGHT, false);

        // Slot labels (BRASS_BRIGHT, above slots)
        ctx.drawText(textRenderer, Text.literal("BODY"),    34, 24, GuiHelper.BRASS_BRIGHT, false);
        ctx.drawText(textRenderer, Text.literal("LENS"),    70, 24, GuiHelper.BRASS_BRIGHT, false);
        ctx.drawText(textRenderer, Text.literal("FILM/SD"), 100, 24, GuiHelper.BRASS_BRIGHT, false);

        // Nameplate text (centered in brass strip, foreground coords)
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("PHOTOGRAPHICA · TRIPOD HEAD"),
                backgroundWidth / 2, 82, GuiHelper.FRAME_LO);

        // Player inventory label
        ctx.drawText(textRenderer, Text.literal("INVENTORY"),
                playerInventoryTitleX, playerInventoryTitleY, GuiHelper.CREAM_DIM, false);
    }
}
