package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * The camera roll: a scrolling grid of everything shot, and a full-screen viewer.
 *
 * <p>Laid out like a phone gallery rather than a file list — newest first, square crops in the
 * grid, click to open, arrow keys or the wheel to move through. Videos cannot play inside
 * Minecraft, so they show their poster frame with a play badge and open in the desktop player.
 */
@Environment(EnvType.CLIENT)
public class GalleryScreen extends Screen {

    private static final int COLS_TARGET_CELL = 116;  // px; column count follows the window
    private static final int PAD              = 8;
    private static final int HEADER_H         = 30;
    private static final int FOOTER_H         = 22;

    private List<MediaLibrary.Entry> entries = List.of();
    private int scroll = 0;
    /** -1 = grid, otherwise the index being viewed full-screen. */
    private int viewing = -1;

    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyy/MM/dd HH:mm");

    public GalleryScreen() {
        super(Text.literal("Gallery"));
    }

    @Override
    protected void init() {
        entries = MediaLibrary.scan();
        if (viewing >= entries.size()) viewing = entries.isEmpty() ? -1 : entries.size() - 1;
        clampScroll();
    }

    @Override
    public boolean shouldPause() { return false; }

    // ── Layout ──────────────────────────────────────────────────────────────────

    private int cols() { return Math.max(1, (width - PAD) / (COLS_TARGET_CELL + PAD)); }
    private int cellW() { return (width - PAD - cols() * PAD) / cols(); }
    private int cellH() { return cellW() * 2 / 3 + 14; }   // 3:2 thumb plus a caption strip
    private int rows()  { return (entries.size() + cols() - 1) / cols(); }

    private int contentH() { return rows() * (cellH() + PAD); }
    private int viewportH() { return height - HEADER_H - FOOTER_H; }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentH() - viewportH())));
    }

    // ── Render ──────────────────────────────────────────────────────────────────

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xF00E0E10);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx, mouseX, mouseY, delta);
        if (viewing >= 0) renderViewer(ctx);
        else              renderGrid(ctx, mouseX, mouseY);
        super.render(ctx, mouseX, mouseY, delta);
    }

    private void renderGrid(DrawContext ctx, int mouseX, int mouseY) {
        ctx.drawTextWithShadow(textRenderer, Text.translatable("snapmatica.gallery.title"), PAD + 2, 10, 0xFFE8DCC4);
        String count = Text.translatable("snapmatica.gallery.items", entries.size()).getString();
        ctx.drawTextWithShadow(textRenderer, Text.literal(count),
                width - PAD - textRenderer.getWidth(count) - 2, 10, 0xFF7A7A85);

        if (entries.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.translatable("snapmatica.gallery.empty"), width / 2, height / 2, 0xFF7A7A85);
            return;
        }

        ctx.enableScissor(0, HEADER_H, width, height - FOOTER_H);
        int c = cols(), cw = cellW(), ch = cellH();
        for (int i = 0; i < entries.size(); i++) {
            int col = i % c, rowI = i / c;
            int x = PAD + col * (cw + PAD);
            int y = HEADER_H + rowI * (ch + PAD) - scroll;
            if (y + ch < HEADER_H || y > height - FOOTER_H) continue;   // off-screen row
            drawCell(ctx, entries.get(i), x, y, cw, ch,
                    mouseX >= x && mouseX < x + cw && mouseY >= y && mouseY < y + ch
                            && mouseY >= HEADER_H && mouseY < height - FOOTER_H);
        }
        ctx.disableScissor();

        if (contentH() > viewportH()) {
            int barH = Math.max(20, viewportH() * viewportH() / contentH());
            int barY = HEADER_H + (viewportH() - barH) * scroll / Math.max(1, contentH() - viewportH());
            ctx.fill(width - 4, barY, width - 1, barY + barH, 0x60FFFFFF);
        }

        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("snapmatica.gallery.help_grid"),
                width / 2, height - FOOTER_H + 6, 0xFF6A6A75);
    }

    private void drawCell(DrawContext ctx, MediaLibrary.Entry e, int x, int y, int w, int h, boolean hover) {
        int thumbH = w * 2 / 3;
        ctx.fill(x, y, x + w, y + thumbH, hover ? 0xFF2A2A32 : 0xFF1A1A20);

        Identifier tex = MediaLibrary.texture(e);
        if (tex != null) {
            drawFitted(ctx, tex, x, y, w, thumbH);
        } else {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    e.video() ? Text.translatable("snapmatica.gallery.video") : Text.literal("..."),
                    x + w / 2, y + thumbH / 2 - 4, 0xFF55555F);
        }

        if (e.video()) {
            // Play badge, so a still and a clip are never confused at a glance.
            int bx = x + w - 16, by = y + thumbH - 14;
            ctx.fill(bx - 2, by - 2, bx + 12, by + 10, 0xB0000000);
            ctx.drawTextWithShadow(textRenderer, Text.literal("▶"), bx, by, 0xFFFFFFFF);
        }

        if (hover) {
            ctx.fill(x, y, x + w, y + 1, 0xFFE8DCC4);
            ctx.fill(x, y + thumbH - 1, x + w, y + thumbH, 0xFFE8DCC4);
            ctx.fill(x, y, x + 1, y + thumbH, 0xFFE8DCC4);
            ctx.fill(x + w - 1, y, x + w, y + thumbH, 0xFFE8DCC4);
        }

        String stamp = STAMP.format(new Date(e.modified()));
        ctx.drawTextWithShadow(textRenderer, Text.literal(stamp), x + 1, y + thumbH + 3, 0xFF8A8A95);
    }

    private void renderViewer(DrawContext ctx) {
        MediaLibrary.Entry e = entries.get(viewing);
        Identifier tex = MediaLibrary.texture(e);

        int top = HEADER_H, bottom = height - FOOTER_H;
        if (tex != null) {
            drawFitted(ctx, tex, PAD, top, width - PAD * 2, bottom - top);
        } else {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.translatable(e.video() ? "snapmatica.gallery.preparing"
                                            : "snapmatica.gallery.unreadable"),
                    width / 2, height / 2, 0xFF7A7A85);
        }

        ctx.drawTextWithShadow(textRenderer, Text.literal(e.displayName()), PAD + 2, 10, 0xFFE8DCC4);
        String pos = (viewing + 1) + " / " + entries.size();
        ctx.drawTextWithShadow(textRenderer, Text.literal(pos),
                width - PAD - textRenderer.getWidth(pos) - 2, 10, 0xFF7A7A85);

        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.translatable(e.video() ? "snapmatica.gallery.help_video"
                                            : "snapmatica.gallery.help_photo"),
                width / 2, height - FOOTER_H + 6, 0xFF6A6A75);
    }

    /** Draws the texture centred and letterboxed inside the box, never stretched. */
    private void drawFitted(DrawContext ctx, Identifier tex, int bx, int by, int bw, int bh) {
        // The mod's own output is 3:2 or 2:3; video posters are 16:9. Assume 3:2 unless the
        // entry is a video, which is close enough for a thumbnail and avoids a size query.
        float ar = 3f / 2f;
        int dw = bw, dh = Math.round(bw / ar);
        if (dh > bh) { dh = bh; dw = Math.round(bh * ar); }
        int dx = bx + (bw - dw) / 2, dy = by + (bh - dh) / 2;
        // Three generations, three signatures: a RenderPipeline in 1.21.11, a RenderLayer
        // factory in 1.21.4, and neither in 1.21.1.
        //? if >=1.21.11 {
        /*ctx.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED,
                tex, dx, dy, 0f, 0f, dw, dh, dw, dh);
        *///?} elif >=1.21.4 {
        /*ctx.drawTexture(net.minecraft.client.render.RenderLayer::getGuiTextured,
                tex, dx, dy, 0f, 0f, dw, dh, dw, dh);
        *///?} else {
        ctx.drawTexture(tex, dx, dy, 0f, 0f, dw, dh, dw, dh);
        //?}
    }

    // ── Input ───────────────────────────────────────────────────────────────────

    /**
     * 1.21.11 replaced the loose (x, y, button) / (key, scancode, mods) parameters with Click
     * and KeyInput records, so each version gets its own thin wrapper over the shared logic.
     */
    //? if >=1.21.11 {
    /*@Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        return onClick(click.x(), click.y(), click.button()) || super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        return onScroll(dy) || super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        return onKey(input.key()) || super.keyPressed(input);
    }
    *///?} else {
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        return onClick(mx, my, button) || super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        return onScroll(dy) || super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean keyPressed(int key, int scancode, int mods) {
        return onKey(key) || super.keyPressed(key, scancode, mods);
    }
    //?}

    private boolean onClick(double mx, double my, int button) {
        if (viewing >= 0) {
            if (button == 0) { open(entries.get(viewing)); return true; }
            return false;
        }
        if (my < HEADER_H || my >= height - FOOTER_H) return false;

        int c = cols(), cw = cellW(), ch = cellH();
        for (int i = 0; i < entries.size(); i++) {
            int x = PAD + (i % c) * (cw + PAD);
            int y = HEADER_H + (i / c) * (ch + PAD) - scroll;
            if (mx >= x && mx < x + cw && my >= y && my < y + ch) { viewing = i; return true; }
        }
        return false;
    }

    private boolean onScroll(double dy) {
        if (viewing >= 0) { step(dy > 0 ? -1 : 1); return true; }
        scroll -= (int) (dy * 40);
        clampScroll();
        return true;
    }

    private boolean onKey(int key) {
        if (viewing < 0) return false;
        switch (key) {
            case 263 -> { step(-1); return true; }                        // left
            case 262 -> { step(1);  return true; }                        // right
            case 257, 335 -> { open(entries.get(viewing)); return true; } // enter
            case 256 -> { viewing = -1; return true; }                    // esc: back to grid
            default -> { return false; }
        }
    }

    private void step(int d) {
        if (entries.isEmpty()) return;
        viewing = Math.floorMod(viewing + d, entries.size());
    }

    private void open(MediaLibrary.Entry e) {
        MediaLibrary.openExternally(e.file());
    }
}
