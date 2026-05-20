package dev.hitom.photographica.client.screen;

import net.minecraft.client.gui.DrawContext;

class GuiHelper {
    static final int PANEL_BORDER   = 0xFF3F3F3F;
    static final int PANEL_BG       = 0xFFC6C6C6;
    static final int SLOT_SHADOW    = 0xFF555555;
    static final int SLOT_HIGHLIGHT = 0xFFDDDDDD;
    static final int SLOT_FILL      = 0xFF8B8B8B;
    static final int TEXT_DARK      = 0x404040;

    static void drawPanel(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x, y, x + w, y + h, PANEL_BORDER);
        ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL_BG);
    }

    /** Draw a sunken slot box. x,y are ABSOLUTE screen coords of the slot's top-left (16×16 area). */
    static void drawSlotBox(DrawContext ctx, int x, int y) {
        ctx.fill(x - 1, y - 1, x + 17, y,       SLOT_SHADOW);
        ctx.fill(x - 1, y - 1, x,       y + 17, SLOT_SHADOW);
        ctx.fill(x,     y + 16, x + 17, y + 17, SLOT_HIGHLIGHT);
        ctx.fill(x + 16, y,     x + 17, y + 17, SLOT_HIGHLIGHT);
        ctx.fill(x, y, x + 16, y + 16, SLOT_FILL);
    }

    /** Two-pixel separator (1px dark + 1px light). x,y absolute coords, w = width. */
    static void drawSeparator(DrawContext ctx, int x, int y, int w) {
        ctx.fill(x, y,     x + w, y + 1, SLOT_SHADOW);
        ctx.fill(x, y + 1, x + w, y + 2, SLOT_HIGHLIGHT);
    }
}
