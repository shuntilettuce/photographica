package dev.hitom.photographica.client.screen;

import dev.hitom.photographica.screen.AlbumScreenHandler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;

/**
 * The album's photo grid. Same flat hand-drawn panel treatment as {@code CameraGearScreen} —
 * no bespoke background texture, just filled rects, so a new machine screen never needs new art
 * to be usable. Slot count (and therefore height) follows the album's own capacity rather than
 * being fixed, since a bigger album is a real possibility later.
 */
@Environment(EnvType.CLIENT)
public class AlbumScreen extends HandledScreen<AlbumScreenHandler> {

    private static final int PANEL = 0xF0201613;
    private static final int PANEL_EDGE = 0xFF4A3B2F;
    private static final int SLOT_BG = 0xFF14100D;
    private static final int SLOT_EDGE = 0xFF6B5744;
    private static final int TEXT = 0xFFE8DCC4;

    private final int rows;

    public AlbumScreen(AlbumScreenHandler handler, PlayerInventory inventory, Text title) {
        super(handler, inventory, title);
        this.rows = Math.max(1, (handler.albumSlotCount() + 8) / 9);
        this.backgroundWidth = 176;
        this.backgroundHeight = 18 + rows * 18 + 14 + 76 + 14;
        this.playerInventoryTitleY = this.backgroundHeight - 94;
    }

    @Override
    protected void drawBackground(DrawContext ctx, float delta, int mouseX, int mouseY) {
        int x = this.x;
        int y = this.y;
        ctx.fill(x - 1, y - 1, x + backgroundWidth + 1, y + backgroundHeight + 1, PANEL_EDGE);
        ctx.fill(x, y, x + backgroundWidth, y + backgroundHeight, PANEL);

        int albumSlots = handler.albumSlotCount();
        for (int i = 0; i < albumSlots; i++) {
            int col = i % 9;
            int row = i / 9;
            int sx = x + 8 + col * 18;
            int sy = y + 18 + row * 18;
            ctx.fill(sx - 1, sy - 1, sx + 17, sy + 17, SLOT_EDGE);
            ctx.fill(sx, sy, sx + 16, sy + 16, SLOT_BG);
        }

        int invY = y + 18 + rows * 18 + 14;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int sx = x + 8 + col * 18;
                int sy = invY + row * 18;
                ctx.fill(sx - 1, sy - 1, sx + 17, sy + 17, SLOT_BG);
            }
        }
        for (int col = 0; col < 9; col++) {
            int sx = x + 8 + col * 18;
            int sy = invY + 58;
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
