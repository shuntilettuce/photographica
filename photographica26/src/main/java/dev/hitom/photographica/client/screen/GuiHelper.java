package dev.hitom.photographica.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

class GuiHelper {
    // ── Safelight color palette (ARGB 0xAARRGGBB) ──────────────────────────────
    static final int VOID          = 0xFF0A0807;
    static final int PANEL_SHADOW  = 0xFF15110D;
    static final int PANEL         = 0xFF221D18;
    static final int PANEL_2       = 0xFF2C2620;
    static final int PANEL_LIGHT   = 0xFF3A3128;
    static final int BRASS_DIM     = 0xFF5C3F18;
    static final int BRASS         = 0xFF9B6F30;
    static final int BRASS_BRIGHT  = 0xFFD4A052;
    static final int CREAM_FAINT   = 0xFF5A5040;
    static final int CREAM_DIM     = 0xFF9A8D72;
    static final int CREAM         = 0xFFE8DCC4;
    static final int SAFELIGHT_DIM = 0xFF7A1F17;
    static final int SAFELIGHT     = 0xFFC2362B;
    static final int EMBER_DIM     = 0xFF8A4F1F;
    static final int EMBER         = 0xFFE08A3C;
    static final int FRAME_HI      = 0xFF4A3F30;
    static final int FRAME_LO      = 0xFF14100C;

    // Alias kept for any callers that reference TEXT_DARK
    static final int TEXT_DARK     = CREAM;

    // ── Convenience fill ────────────────────────────────────────────────────────
    static void fill(GuiGraphics ctx, int x1, int y1, int x2, int y2, int color) {
        ctx.fill(x1, y1, x2, y2, color);
    }

    // ── Panel (main panel surface with bevel) ────────────────────────────────────
    static void drawPanel(GuiGraphics ctx, int x, int y, int w, int h) {
        ctx.fill(x, y, x + w, y + h, PANEL);
        // outer border (brass)
        ctx.fill(x,         y,         x + w,     y + 1,     BRASS_DIM);
        ctx.fill(x,         y + h - 1, x + w,     y + h,     BRASS_DIM);
        ctx.fill(x,         y,         x + 1,     y + h,     BRASS_DIM);
        ctx.fill(x + w - 1, y,         x + w,     y + h,     BRASS_DIM);
        // inner bevel
        ctx.fill(x + 1,     y + 1,     x + w - 1, y + 2,     PANEL_LIGHT);
        ctx.fill(x + 1,     y + 1,     x + 2,     y + h - 1, PANEL_LIGHT);
        ctx.fill(x + 1,     y + h - 2, x + w - 1, y + h - 1, PANEL_SHADOW);
        ctx.fill(x + w - 2, y + 1,     x + w - 1, y + h - 1, PANEL_SHADOW);
    }

    // ── Slot (normal, brass ring) ────────────────────────────────────────────────
    // ix,iy = top-left of the inner 16×16 area (absolute screen coords)
    static void drawSlot(GuiGraphics ctx, int ix, int iy) {
        ctx.fill(ix - 2, iy - 2, ix + 18, iy + 18, PANEL_SHADOW);  // outer border
        ctx.fill(ix - 1, iy - 1, ix + 17, iy + 17, BRASS_DIM);     // brass ring
        ctx.fill(ix - 1, iy - 1, ix + 17, iy,      FRAME_LO);      // top bevel
        ctx.fill(ix - 1, iy - 1, ix,      iy + 17, FRAME_LO);      // left bevel
        ctx.fill(ix - 1, iy + 16, ix + 17, iy + 17, BRASS_BRIGHT); // bottom bevel
        ctx.fill(ix + 16, iy - 1, ix + 17, iy + 17, BRASS_BRIGHT); // right bevel
        ctx.fill(ix,     iy,     ix + 16, iy + 16, VOID);           // interior
    }

    // ── Slot HOT (safelight red ring) ────────────────────────────────────────────
    static void drawSlotHot(GuiGraphics ctx, int ix, int iy) {
        ctx.fill(ix - 2, iy - 2, ix + 18, iy + 18, PANEL_SHADOW);
        ctx.fill(ix - 1, iy - 1, ix + 17, iy + 17, SAFELIGHT_DIM);
        ctx.fill(ix - 1, iy - 1, ix + 17, iy,      FRAME_LO);
        ctx.fill(ix - 1, iy - 1, ix,      iy + 17, FRAME_LO);
        ctx.fill(ix - 1, iy + 16, ix + 17, iy + 17, SAFELIGHT);
        ctx.fill(ix + 16, iy - 1, ix + 17, iy + 17, SAFELIGHT);
        ctx.fill(ix,     iy,     ix + 16, iy + 16, VOID);
    }

    // ── Slot EMBER (orange ring) ──────────────────────────────────────────────────
    static void drawSlotEmber(GuiGraphics ctx, int ix, int iy) {
        ctx.fill(ix - 2, iy - 2, ix + 18, iy + 18, PANEL_SHADOW);
        ctx.fill(ix - 1, iy - 1, ix + 17, iy + 17, EMBER_DIM);
        ctx.fill(ix - 1, iy - 1, ix + 17, iy,      FRAME_LO);
        ctx.fill(ix - 1, iy - 1, ix,      iy + 17, FRAME_LO);
        ctx.fill(ix - 1, iy + 16, ix + 17, iy + 17, EMBER);
        ctx.fill(ix + 16, iy - 1, ix + 17, iy + 17, EMBER);
        ctx.fill(ix,     iy,     ix + 16, iy + 16, VOID);
    }

    // ── Rule (2px separator: shadow + highlight) ─────────────────────────────────
    static void drawRule(GuiGraphics ctx, int x, int y, int w) {
        ctx.fill(x, y,     x + w, y + 1, PANEL_SHADOW);
        ctx.fill(x, y + 1, x + w, y + 2, PANEL_LIGHT);
    }

    // ── Nameplate (9px brass strip + 1px shadow) ─────────────────────────────────
    static void drawNameplate(GuiGraphics ctx, int x, int y, int w) {
        ctx.fill(x, y,     x + w, y + 1, 0xFFEFC88A);   // top inner highlight
        ctx.fill(x, y + 1, x + w, y + 3, BRASS_BRIGHT); // bright brass
        ctx.fill(x, y + 3, x + w, y + 6, BRASS);        // mid brass
        ctx.fill(x, y + 6, x + w, y + 8, BRASS_DIM);    // dark brass
        ctx.fill(x, y + 8, x + w, y + 9, 0xFF3A2812);   // darkest
        ctx.fill(x, y + 9, x + w, y + 10, PANEL_SHADOW);// shadow below
    }

    // ── LCD display background ────────────────────────────────────────────────────
    static void drawLcd(GuiGraphics ctx, int x, int y, int w, int h) {
        ctx.fill(x - 1, y - 1, x + w + 1, y + h + 1, BRASS_DIM);  // outer border
        ctx.fill(x, y, x + w, y + h, 0xFF1A0A06);                  // dark background
        ctx.fill(x, y, x + w, y + 1, 0xFF2A0D08);                  // inner top bevel
        ctx.fill(x, y, x + 1, y + h, 0xFF2A0D08);                  // inner left bevel
    }

    // ── Well (engraved trough) ────────────────────────────────────────────────────
    static void drawWell(GuiGraphics ctx, int x, int y, int w, int h) {
        ctx.fill(x,         y,         x + w,     y + h,     0xFF110D0A);
        ctx.fill(x,         y,         x + w,     y + 1,     PANEL_SHADOW);
        ctx.fill(x,         y,         x + 1,     y + h,     PANEL_SHADOW);
        ctx.fill(x,         y + h - 1, x + w,     y + h,     PANEL_LIGHT);
        ctx.fill(x + w - 1, y,         x + w,     y + h,     PANEL_LIGHT);
    }

    // ── Pip (3×3 indicator) ───────────────────────────────────────────────────────
    static final int PIP_OFF   = CREAM_FAINT;
    static final int PIP_RED   = SAFELIGHT;
    static final int PIP_EMBER = EMBER;

    static void drawPip(GuiGraphics ctx, int x, int y, int color) {
        ctx.fill(x, y, x + 3, y + 3, color);
    }

    // ── Separator alias (for legacy callers) ──────────────────────────────────────
    static void drawSeparator(GuiGraphics ctx, int x, int y, int w) {
        drawRule(ctx, x, y, w);
    }

    // ── SlotBox alias (for legacy callers) ────────────────────────────────────────
    static void drawSlotBox(GuiGraphics ctx, int x, int y) {
        drawSlot(ctx, x, y);
    }

    // ── Player inventory slot grid (36 slots: 3×9 main + 9 hotbar) ───────────────
    static void drawPlayerInventory(GuiGraphics ctx, int panelX, int panelY, int invY, int hotbarY) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                drawSlot(ctx, panelX + 8 + col * 18, panelY + invY + row * 18);
            }
        }
        for (int col = 0; col < 9; col++) {
            drawSlot(ctx, panelX + 8 + col * 18, panelY + hotbarY);
        }
    }
}
