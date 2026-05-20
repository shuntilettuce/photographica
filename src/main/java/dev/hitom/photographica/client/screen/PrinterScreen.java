package dev.hitom.photographica.client.screen;

import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.component.SdCardData;
import dev.hitom.photographica.screen.PrinterScreenHandler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

@Environment(EnvType.CLIENT)
public class PrinterScreen extends HandledScreen<PrinterScreenHandler> {

    public PrinterScreen(PrinterScreenHandler handler, PlayerInventory playerInventory, Text title) {
        super(handler, playerInventory, title);
        this.backgroundWidth  = 176;
        this.backgroundHeight = 176;
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

        // LCD background top-right (format display)
        GuiHelper.drawLcd(ctx, x + w - 42, y + 3, 36, 9);

        // Left mini-LCDs: USB-C and A6
        GuiHelper.drawLcd(ctx, x + 6, y + 36, 32, 9);
        GuiHelper.drawLcd(ctx, x + 6, y + 46, 32, 9);

        // Right of paper slot: QUEUE LCD showing photo count
        GuiHelper.drawLcd(ctx, x + 102, y + 44, 32, 9);

        // Player inventory slots
        GuiHelper.drawPlayerInventory(ctx, x, y, 94, 152);

        // Slots: SD at (44,35), PAPER at (80,35)
        GuiHelper.drawSlot(ctx, x + 44, y + 35);
        GuiHelper.drawSlot(ctx, x + 80, y + 35);

        // Data transfer arrows between slots (ember color, at y=42)
        ctx.fill(x + 62, y + 41, x + 79, y + 42, GuiHelper.EMBER_DIM);
        ctx.fill(x + 62, y + 43, x + 79, y + 44, GuiHelper.EMBER);
        ctx.fill(x + 62, y + 45, x + 79, y + 46, GuiHelper.EMBER_DIM);

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
        ctx.drawText(textRenderer, Text.literal("PRINTER"), 9, 5, GuiHelper.CREAM, false);

        // LCD text top-right (format)
        ctx.drawText(textRenderer, Text.literal("JPG"), backgroundWidth - 41, 4, GuiHelper.EMBER, false);

        // Left mini-LCD labels
        ctx.drawText(textRenderer, Text.literal("USB-C"), 8, 37, GuiHelper.SAFELIGHT, false);
        ctx.drawText(textRenderer, Text.literal("A6"),    8, 47, GuiHelper.EMBER,     false);

        // Slot labels (BRASS_BRIGHT, above slots)
        ctx.drawText(textRenderer, Text.literal("SD"),    38, 24, GuiHelper.BRASS_BRIGHT, false);
        ctx.drawText(textRenderer, Text.literal("PAPER"), 74, 24, GuiHelper.BRASS_BRIGHT, false);

        // Right of paper slot: queue label + photo count
        ctx.drawText(textRenderer, Text.literal("QUEUE"), 102, 36, GuiHelper.CREAM_DIM, false);

        // Photo count from SD card
        ItemStack sd = this.handler.getSlot(0).getStack();
        if (!sd.isEmpty() && sd.contains(ModDataComponents.SD_CARD)) {
            SdCardData data = sd.getOrDefault(ModDataComponents.SD_CARD, SdCardData.EMPTY);
            if (!data.isEmpty()) {
                String count = data.photos().size() + "ph";
                ctx.drawText(textRenderer, Text.literal(count), 104, 45, GuiHelper.EMBER, false);
            }
        }

        // Nameplate text
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("P-7 · DYE SUBLIMATION"),
                backgroundWidth / 2, 82, GuiHelper.CREAM_DIM);

    }
}
