package dev.hitom.photographica.client.screen;

import dev.hitom.photographica.component.FilmRollData;
import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.screen.EnlargerScreenHandler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public class EnlargerScreen extends HandledScreen<EnlargerScreenHandler> {

    public EnlargerScreen(EnlargerScreenHandler handler, PlayerInventory playerInventory, Text title) {
        super(handler, playerInventory, title);
        this.backgroundWidth  = 176;
        this.backgroundHeight = 172;
    }

    @Override
    protected void init() {
        super.init();
        int x = this.x, y = this.y;
        addDrawableChild(SafelightButton.primary(x + 6, y + 58, 80, Text.literal("PRINT ALL"),
                b -> this.client.interactionManager.clickButton(this.handler.syncId, 0)));
        addDrawableChild(SafelightButton.of(x + 90, y + 58, 80, Text.literal("SINGLE"),
                b -> this.client.interactionManager.clickButton(this.handler.syncId, 1)));
    }

    @Override
    protected void drawBackground(DrawContext ctx, float delta, int mouseX, int mouseY) {
        int x = this.x, y = this.y, w = backgroundWidth, h = backgroundHeight;

        GuiHelper.drawPanel(ctx, x, y, w, h);

        // Rule at y=14
        GuiHelper.drawRule(ctx, x + 6, y + 14, w - 12);

        // LCD background top-right (timer display)
        GuiHelper.drawLcd(ctx, x + w - 42, y + 3, 36, 9);

        // Left mini-LCDs: f/8 and 12s
        GuiHelper.drawLcd(ctx, x + 6, y + 36, 32, 9);
        GuiHelper.drawLcd(ctx, x + 6, y + 46, 32, 9);

        // Slots: NEGATIVE at (44,35), PAPER at (80,35)
        GuiHelper.drawSlot(ctx, x + 44, y + 35);
        GuiHelper.drawSlot(ctx, x + 80, y + 35);

        // Light beam between slots (3 horizontal lines)
        ctx.fill(x + 62, y + 38, x + 79, y + 39, GuiHelper.EMBER_DIM);
        ctx.fill(x + 62, y + 42, x + 79, y + 43, GuiHelper.EMBER);
        ctx.fill(x + 62, y + 46, x + 79, y + 47, GuiHelper.EMBER_DIM);

        // Focus reticle right of paper slot at (100,39): 5×5 box with safelight crosshair
        ctx.fill(x + 100, y + 39, x + 105, y + 44, GuiHelper.PANEL_SHADOW); // box background
        // Brass border (1px)
        ctx.fill(x + 100, y + 39, x + 105, y + 40, GuiHelper.BRASS_DIM); // top
        ctx.fill(x + 100, y + 43, x + 105, y + 44, GuiHelper.BRASS_DIM); // bottom
        ctx.fill(x + 100, y + 39, x + 101, y + 44, GuiHelper.BRASS_DIM); // left
        ctx.fill(x + 104, y + 39, x + 105, y + 44, GuiHelper.BRASS_DIM); // right
        // Safelight crosshair
        ctx.fill(x + 102, y + 39, x + 103, y + 44, GuiHelper.SAFELIGHT); // vertical
        ctx.fill(x + 100, y + 41, x + 105, y + 42, GuiHelper.SAFELIGHT); // horizontal

        // Nameplate
        GuiHelper.drawNameplate(ctx, x + 6, y + 80, 164);
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
        ctx.drawText(textRenderer, Text.literal("ENLARGER"), 9, 5, GuiHelper.CREAM, false);

        // LCD text top-right (timer)
        ctx.drawText(textRenderer, Text.literal("12s"), backgroundWidth - 41, 4, GuiHelper.EMBER, false);

        // Left mini-LCD labels
        ctx.drawText(textRenderer, Text.literal("f/8"), 8, 37, GuiHelper.SAFELIGHT, false);
        ctx.drawText(textRenderer, Text.literal("12s"), 8, 47, GuiHelper.EMBER, false);

        // Slot labels (BRASS_BRIGHT, above slots)
        ctx.drawText(textRenderer, Text.literal("NEG"),   36, 24, GuiHelper.BRASS_BRIGHT, false);
        ctx.drawText(textRenderer, Text.literal("PAPER"), 74, 24, GuiHelper.BRASS_BRIGHT, false);

        // Film roll exposure count (on neg slot if film loaded)
        ItemStack film = this.handler.getSlot(0).getStack();
        if (!film.isEmpty() && film.contains(ModDataComponents.FILM_ROLL)) {
            FilmRollData data = film.get(ModDataComponents.FILM_ROLL);
            if (data != null && !data.exposures().isEmpty()) {
                String count = data.exposures().size() + "fr";
                int tx = 44 + 8 - textRenderer.getWidth(count) / 2;
                ctx.drawText(textRenderer, Text.literal(count), tx, 35, GuiHelper.EMBER, false);
            }
        }

        // Nameplate text
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("DURST M605 · COLD HEAD"),
                backgroundWidth / 2, 82, GuiHelper.FRAME_LO);

        // Player inventory label
        ctx.drawText(textRenderer, Text.literal("INVENTORY"),
                playerInventoryTitleX, playerInventoryTitleY, GuiHelper.CREAM_DIM, false);
    }
}
