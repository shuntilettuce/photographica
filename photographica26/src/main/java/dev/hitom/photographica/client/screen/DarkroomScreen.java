package dev.hitom.photographica.client.screen;

import dev.hitom.photographica.screen.DarkroomScreenHandler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

@Environment(EnvType.CLIENT)
public class DarkroomScreen extends AbstractContainerScreen<DarkroomScreenHandler> {

    public DarkroomScreen(DarkroomScreenHandler handler, Inventory playerInventory, Component title) {
        super(handler, playerInventory, title);
        this.imageWidth  = 176;
        this.imageHeight = 186;
    }

    @Override
    protected void init() {
        super.init();

        int x = this.leftPos, y = this.topPos;
        addRenderableWidget(SafelightButton.of(x + 6, y + 72, 80, Component.literal("DEVELOP"),
                b -> this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, 0)));
        addRenderableWidget(SafelightButton.primary(x + 90, y + 72, 80, Component.literal("CAM → DEV"),
                b -> this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, 1)));
    }

    @Override
    protected void renderBg(GuiGraphics ctx, float delta, int mouseX, int mouseY) {
        int x = this.leftPos, y = this.topPos, w = imageWidth, h = imageHeight;

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
        boolean cameraLoaded = this.menu.getSlot(4).hasItem();
        if (cameraLoaded) {
            GuiHelper.drawSlotHot(ctx, x + 80, y + 52);
        } else {
            GuiHelper.drawSlot(ctx, x + 80, y + 52);
        }

        // Rule above buttons
        GuiHelper.drawRule(ctx, x + 6, y + 70, w - 12);

        // Player inventory slots
        GuiHelper.drawPlayerInventory(ctx, x, y, 106, 164);

        // Nameplate
        GuiHelper.drawNameplate(ctx, x + 6, y + 93, 164);
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground(ctx, mouseX, mouseY, delta);
        super.render(ctx, mouseX, mouseY, delta);
        this.renderTooltip(ctx, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics ctx, int mouseX, int mouseY) {
        // Pip + title
        ctx.fill(3, 5, 6, 8, GuiHelper.SAFELIGHT);
        ctx.drawString(font, Component.literal("PORTABLE DARKROOM"), 9, 5, GuiHelper.CREAM, false);

        // LCD text "SAFE"
        ctx.drawString(font, Component.literal("SAFE"),
                imageWidth - 41, 4, GuiHelper.SAFELIGHT, false);

        // Slot labels
        ctx.drawString(font, Component.literal("FILM"),  43, 45, GuiHelper.CREAM_DIM, false);
        ctx.drawString(font, Component.literal("CHEM"), 132, 45, GuiHelper.EMBER,     false);

        // Camera slot labels
        ctx.drawString(font, Component.literal("CAMERA"), 100, 54, GuiHelper.BRASS_BRIGHT, false);
        ctx.drawString(font, Component.literal("IN"),       50, 54, GuiHelper.CREAM_DIM,   false);

        // Camera loaded pip + status text
        boolean cameraLoaded = this.menu.getSlot(4).hasItem();
        ctx.fill(72, 58, 75, 61, cameraLoaded ? GuiHelper.SAFELIGHT : GuiHelper.PIP_OFF);
        if (cameraLoaded) {
            ctx.drawString(font, Component.literal("LOADED"), 100, 62, GuiHelper.CREAM_DIM, false);
        }

        // Nameplate text
        ctx.drawCenteredString(font,
                Component.literal("DR-1 · 35MM CHEMICAL BATH"),
                imageWidth / 2, 95, GuiHelper.CREAM_DIM);
    }
}
