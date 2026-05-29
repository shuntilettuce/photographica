package dev.hitom.photographica.client.screen;

import dev.hitom.photographica.component.FilmRollData;
import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.screen.EnlargerScreenHandler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

@Environment(EnvType.CLIENT)
public class EnlargerScreen extends AbstractContainerScreen<EnlargerScreenHandler> {

    public EnlargerScreen(EnlargerScreenHandler handler, Inventory playerInventory, Component title) {
        super(handler, playerInventory, title, 176, 176);
    }

    protected void init() {
        super.init();
        int x = this.leftPos, y = this.topPos;
        addRenderableWidget(SafelightButton.primary(x + 6, y + 58, 80, Component.literal("PRINT ALL"),
                b -> this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, 0)));
        addRenderableWidget(SafelightButton.of(x + 90, y + 58, 80, Component.literal("SINGLE"),
                b -> this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, 1)));
    }

    public void extractContents(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        int x = this.leftPos, y = this.topPos, w = imageWidth, h = imageHeight;

        GuiHelper.drawPanel(ctx, x, y, w, h);

        // Rule at y=14
        GuiHelper.drawRule(ctx, x + 6, y + 14, w - 12);

        // LCD background top-right (timer display)
        GuiHelper.drawLcd(ctx, x + w - 42, y + 3, 36, 9);

        // Left mini-LCDs: f/8 and 12s
        GuiHelper.drawLcd(ctx, x + 6, y + 36, 32, 9);
        GuiHelper.drawLcd(ctx, x + 6, y + 46, 32, 9);

        // Player inventory slots
        GuiHelper.drawPlayerInventory(ctx, x, y, 94, 152);

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
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        this.extractBackground(ctx, mouseX, mouseY, delta);
        super.extractRenderState(ctx, mouseX, mouseY, delta);
        this.extractTooltip(ctx, mouseX, mouseY);
    }

    protected void extractLabels(GuiGraphicsExtractor ctx, int mouseX, int mouseY) {
        // Pip + title
        ctx.fill(3, 5, 6, 8, GuiHelper.SAFELIGHT);
        ctx.text(font, Component.literal("ENLARGER"), 9, 5, GuiHelper.CREAM, false);

        // LCD text top-right (timer)
        ctx.text(font, Component.literal("12s"), imageWidth - 41, 4, GuiHelper.EMBER, false);

        // Left mini-LCD labels
        ctx.text(font, Component.literal("f/8"), 8, 37, GuiHelper.SAFELIGHT, false);
        ctx.text(font, Component.literal("12s"), 8, 47, GuiHelper.EMBER, false);

        // Slot labels (BRASS_BRIGHT, above slots)
        ctx.text(font, Component.literal("NEG"),   36, 24, GuiHelper.BRASS_BRIGHT, false);
        ctx.text(font, Component.literal("PAPER"), 74, 24, GuiHelper.BRASS_BRIGHT, false);

        // Film roll exposure count (on neg slot if film loaded)
        ItemStack film = this.menu.getSlot(0).getItem();
        if (!film.isEmpty() && film.has(ModDataComponents.FILM_ROLL)) {
            FilmRollData data = film.get(ModDataComponents.FILM_ROLL);
            if (data != null && !data.exposures().isEmpty()) {
                String count = data.exposures().size() + "fr";
                int tx = 44 + 8 - font.width(count) / 2;
                ctx.text(font, Component.literal(count), tx, 35, GuiHelper.EMBER, false);
            }
        }

        // Nameplate text
        ctx.centeredText(font,
                Component.literal("DURST M605 · COLD HEAD"),
                imageWidth / 2, 82, GuiHelper.CREAM_DIM);
    }
}
