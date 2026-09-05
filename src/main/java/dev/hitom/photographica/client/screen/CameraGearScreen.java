package dev.hitom.photographica.client.screen;

import dev.hitom.photographica.component.CameraGear;
import dev.hitom.photographica.screen.CameraGearScreenHandler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

/**
 * The camera-body slots. Drawn with the same flat panel treatment the other machine screens in
 * this mod use rather than a bespoke background texture, so it needs no new art to be legible.
 * Each empty slot is labelled, because "which of these four holes takes the battery" is not
 * something an unlabelled grid answers.
 */
@Environment(EnvType.CLIENT)
public class CameraGearScreen extends HandledScreen<CameraGearScreenHandler> {

    private static final int PANEL = 0xF0201613;
    private static final int PANEL_EDGE = 0xFF4A3B2F;
    private static final int SLOT_BG = 0xFF14100D;
    private static final int SLOT_EDGE = 0xFF6B5744;
    private static final int TEXT = 0xFFE8DCC4;
    private static final int TEXT_DIM = 0xFF9A8D72;

    private static final String[] SLOT_LABELS = {"レンズ", "記録", "電池", "閃光"};
    private static final int[] SLOT_X = {44, 71, 98, 125};
    private static final int SLOT_Y = 20;

    public CameraGearScreen(CameraGearScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        this.backgroundWidth = 176;
        this.backgroundHeight = 133;
        this.playerInventoryTitleY = this.backgroundHeight - 94;
    }

    //? if >=1.21.11 {
    /*@Override
    protected void drawBackground(DrawContext ctx, float delta, int mouseX, int mouseY) {
        drawPanel(ctx);
    }
    *///?} else {
    @Override
    protected void drawBackground(DrawContext ctx, float delta, int mouseX, int mouseY) {
        drawPanel(ctx);
    }
    //?}

    private void drawPanel(DrawContext ctx) {
        int x = this.x;
        int y = this.y;
        ctx.fill(x - 1, y - 1, x + backgroundWidth + 1, y + backgroundHeight + 1, PANEL_EDGE);
        ctx.fill(x, y, x + backgroundWidth, y + backgroundHeight, PANEL);

        for (int i = 0; i < CameraGear.SLOT_COUNT; i++) {
            int sx = x + SLOT_X[i];
            int sy = y + SLOT_Y;
            ctx.fill(sx - 2, sy - 2, sx + 18, sy + 18, SLOT_EDGE);
            ctx.fill(sx - 1, sy - 1, sx + 17, sy + 17, SLOT_BG);
            // Label under each slot. Drawn regardless of whether the slot is filled — the item
            // sprite alone doesn't say what the slot is FOR once it's occupied either.
            String label = SLOT_LABELS[i];
            int lw = this.textRenderer.getWidth(label);
            ctx.drawText(this.textRenderer, label, sx + 8 - lw / 2, sy + 20, TEXT_DIM, false);
        }

        // Player inventory slot wells.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int sx = x + 8 + col * 18;
                int sy = y + 51 + row * 18;
                ctx.fill(sx - 1, sy - 1, sx + 17, sy + 17, SLOT_BG);
            }
        }
        for (int col = 0; col < 9; col++) {
            int sx = x + 8 + col * 18;
            int sy = y + 109;
            ctx.fill(sx - 1, sy - 1, sx + 17, sy + 17, SLOT_BG);
        }
    }

    @Override
    protected void drawForeground(DrawContext ctx, int mouseX, int mouseY) {
        ctx.drawText(this.textRenderer, this.title, 8, 6, TEXT, false);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground(ctx, mouseX, mouseY, delta);
        super.render(ctx, mouseX, mouseY, delta);
        this.drawMouseoverTooltip(ctx, mouseX, mouseY);
    }
}
