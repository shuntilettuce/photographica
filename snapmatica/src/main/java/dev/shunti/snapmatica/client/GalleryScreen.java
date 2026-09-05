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
    /** Densities the roll offers. 0 is "let the window decide", which is where it starts. */
    private static final int[] COL_CHOICES = {0, 3, 4, 5, 6, 8, 10};
    /** The scrollbar is a control now, so it has to be wide enough to catch and to see. */
    private static final int BAR_W  = 6;
    private static final int BAR_HIT = 14;
    private static final int PAD              = 8;
    /** Breathing room between the metadata column and the picture. */
    private static final int EXIF_GAP = 10;
    private static final int HEADER_H         = 30;
    private static final int HINT_H           = 14;   // the one-line hint under the grid
    private static final int BUTTON_H         = 20;
    private static final int FOOTER_H         = HINT_H + BUTTON_H + 10;

    private List<MediaLibrary.Entry> entries = List.of();
    private int scroll = 0;
    /** Set while the scrollbar is being dragged, with the grab point inside the thumb. */
    private boolean draggingBar = false;
    private int dragGrab = 0;
    /** -1 = grid, otherwise the index being viewed full-screen. */
    private int viewing = -1;

    /**
     * The action row. These are real widgets rather than painted rectangles so they narrate,
     * highlight and click like every other button in the game; they are simply hidden while
     * the grid is up, since they act on the item being viewed.
     */
    private CameraUi.Button copyBtn, revealBtn, openBtn, deleteBtn, backBtn, colsBtn;
    /** Back sits at the end of the action row in the viewer, but alone it belongs centred. */
    private int backViewerX, backGridX;

    /**
     * Delete asks once before it acts: the first press arms it and relabels the button as a
     * question, the second within a few seconds carries it out. Armed state clears itself on a
     * timeout or on leaving the shot being armed for, so a delete can never land on whatever
     * happens to be on screen minutes later.
     */
    private boolean deleteArmed = false;
    private long    deleteArmedAt = 0L;
    private static final long DELETE_CONFIRM_TIMEOUT_MS = 4000L;

    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyy/MM/dd HH:mm");

    public GalleryScreen() {
        super(Text.literal("Gallery"));
    }

    @Override
    protected void init() {
        entries = MediaLibrary.scan();
        if (viewing >= entries.size()) viewing = entries.isEmpty() ? -1 : entries.size() - 1;
        clampScroll();

        int by = height - BUTTON_H - 6;
        int gap = 4;
        int wCopy = 84, wReveal = 116, wOpen = 84, wDelete = 84, wBack = 64;
        int total = wCopy + wReveal + wOpen + wDelete + wBack + gap * 4;
        int x = (width - total) / 2;

        copyBtn = CameraUi.Button.of(x, by, wCopy,
                Text.translatable("snapmatica.gallery.copy"),
                b -> { if (viewing >= 0) MediaLibrary.copyToClipboard(entries.get(viewing)); });
        x += wCopy + gap;
        revealBtn = CameraUi.Button.of(x, by, wReveal,
                Text.translatable("snapmatica.gallery.reveal"),
                b -> { if (viewing >= 0) MediaLibrary.revealInFolder(entries.get(viewing).file()); });
        x += wReveal + gap;
        openBtn = CameraUi.Button.primary(x, by, wOpen,
                Text.translatable("snapmatica.gallery.open"),
                b -> { if (viewing >= 0) open(entries.get(viewing)); });
        x += wOpen + gap;
        deleteBtn = CameraUi.Button.ghost(x, by, wDelete,
                Text.translatable("snapmatica.gallery.delete"),
                b -> onDeletePressed());
        x += wDelete + gap;
        backViewerX = x;
        backGridX = (width - wBack) / 2;
        backBtn = CameraUi.Button.ghost(x, by, wBack,
                Text.translatable("snapmatica.common.close"),
                b -> { if (viewing >= 0) { leaveViewer(); } else close(); });

        colsBtn = CameraUi.Button.ghost(PAD, by, 78,
                Text.translatable("snapmatica.gallery.cols", colsLabel()),
                b -> cycleCols(1));

        addDrawableChild(colsBtn);
        addDrawableChild(copyBtn);
        addDrawableChild(revealBtn);
        addDrawableChild(openBtn);
        addDrawableChild(deleteBtn);
        addDrawableChild(backBtn);
        refreshActions();
    }

    /** Keeps the action row in step with what is on screen. */
    private void refreshActions() {
        boolean viewer = viewing >= 0;
        if (colsBtn != null) {
            colsBtn.visible = !viewer && !entries.isEmpty();
            colsBtn.setMessage(Text.translatable("snapmatica.gallery.cols", colsLabel()));
        }
        copyBtn.visible = revealBtn.visible = openBtn.visible = deleteBtn.visible = viewer;
        backBtn.setX(viewer ? backViewerX : backGridX);
        backBtn.setMessage(Text.translatable(viewer ? "snapmatica.common.back"
                                                    : "snapmatica.common.close"));
        if (viewer) {
            openBtn.setMessage(Text.translatable(entries.get(viewing).video()
                    ? "snapmatica.gallery.play" : "snapmatica.gallery.open"));
            deleteBtn.setMessage(Text.translatable(deleteArmed
                    ? "snapmatica.gallery.delete_confirm" : "snapmatica.gallery.delete"));
        }
    }

    private void onDeletePressed() {
        if (viewing < 0) return;
        if (deleteArmed) {
            MediaLibrary.Entry e = entries.get(viewing);
            MediaLibrary.deleteEntry(e);
            deleteArmed = false;
            entries = MediaLibrary.scan();
            viewing = entries.isEmpty() ? -1 : Math.min(viewing, entries.size() - 1);
            clampScroll();
            refreshActions();
        } else {
            deleteArmed = true;
            deleteArmedAt = System.currentTimeMillis();
            refreshActions();
        }
    }

    /** Leaves the viewer for the grid, disarming any pending delete confirmation. */
    private void leaveViewer() {
        viewing = -1;
        deleteArmed = false;
        refreshActions();
    }

    @Override
    public boolean shouldPause() { return false; }

    // ── Layout ──────────────────────────────────────────────────────────────────

    private int cols() {
        int fixed = SnapmaticaClient.galleryCols;
        if (fixed > 0) return Math.max(1, fixed);
        return Math.max(1, (width - PAD) / (COLS_TARGET_CELL + PAD));
    }

    /** Step the density on, wrapping. Keeps the top-left picture in view. */
    private void cycleCols(int dir) {
        int cur = 0;
        for (int i = 0; i < COL_CHOICES.length; i++) {
            if (COL_CHOICES[i] == SnapmaticaClient.galleryCols) { cur = i; break; }
        }
        int n = COL_CHOICES.length;
        SnapmaticaClient.galleryCols = COL_CHOICES[((cur + dir) % n + n) % n];
        SnapmaticaConfig.save();
        // The row the top of the viewport was showing, kept across the change, so the roll does
        // not jump somewhere else the moment the density changes.
        clampScroll();
        refreshActions();
    }

    private String colsLabel() {
        return SnapmaticaClient.galleryCols > 0
                ? String.valueOf(SnapmaticaClient.galleryCols)
                : Text.translatable("snapmatica.gallery.cols_auto").getString();
    }

    // ── The scrollbar, as a control ─────────────────────────────────────────────
    /** {trackTop, trackH, thumbY, thumbH}, or null when everything already fits. */
    private int[] barMetrics() {
        int content = contentH(), view = viewportH();
        if (content <= view) return null;
        int thumbH = Math.max(20, view * view / content);
        int thumbY = HEADER_H + (view - thumbH) * scroll / Math.max(1, content - view);
        return new int[] {HEADER_H, view, thumbY, thumbH};
    }

    private boolean inBarColumn(double mx) { return mx >= width - BAR_HIT; }

    /** Put the thumb's top at {@code y} and read the scroll back off it. */
    private void scrollFromThumbTop(int y) {
        int[] b = barMetrics();
        if (b == null) return;
        int span = b[1] - b[3];
        if (span <= 0) { scroll = 0; return; }
        scroll = (int) ((long) (y - b[0]) * (contentH() - viewportH()) / span);
        clampScroll();
    }
    private int cellW() { return (width - PAD - cols() * PAD) / cols(); }
    private int cellH() { return cellW() * 2 / 3 + 14; }   // 3:2 thumb plus a caption strip
    private int rows()  { return (entries.size() + cols() - 1) / cols(); }

    private int contentH() { return rows() * (cellH() + PAD); }
    private int viewportH() { return height - HEADER_H - FOOTER_H; }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentH() - viewportH())));
    }

    // ── Render ──────────────────────────────────────────────────────────────────

    /**
     * Deliberately empty. Before 1.21.11, {@code Screen.render} opens by calling this, which
     * lands *after* this screen has drawn its own content — a second coat of the backdrop that
     * buried everything under it. The backdrop is painted from {@link #render} instead, where
     * the order is ours to control on every version.
     */
    //? if >=1.21 {
    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {}
    //?} else {
    /*@Override
    public void renderBackground(DrawContext ctx) {}
    *///?}

    /** The dimmed backdrop this screen sits on. */
    private void drawBackdrop(DrawContext ctx) {
        ctx.fill(0, 0, width, height, 0xF00E0E10);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (deleteArmed && System.currentTimeMillis() - deleteArmedAt > DELETE_CONFIRM_TIMEOUT_MS) {
            deleteArmed = false;
            refreshActions();
        }
        drawBackdrop(ctx);
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

        int[] bar = barMetrics();
        if (bar != null) {
            int bx = width - 2 - BAR_W;
            boolean hot = draggingBar || (mouseX >= width - BAR_HIT
                    && mouseY >= HEADER_H && mouseY < height - FOOTER_H);
            ctx.fill(bx, bar[0], bx + BAR_W, bar[0] + bar[1], 0x30000000);
            ctx.fill(bx, bar[2], bx + BAR_W, bar[2] + bar[3],
                    hot ? 0xC0E8DCC4 : 0x60FFFFFF);
        }

        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("snapmatica.gallery.help_grid"),
                width / 2, height - FOOTER_H + 3, CameraUi.CREAM_DIM);
    }

    private void drawCell(DrawContext ctx, MediaLibrary.Entry e, int x, int y, int w, int h, boolean hover) {
        int thumbH = w * 2 / 3;
        ctx.fill(x, y, x + w, y + thumbH, hover ? 0xFF2A2A32 : 0xFF1A1A20);

        Identifier tex = MediaLibrary.texture(e);
        if (tex != null) {
            drawFitted(ctx, tex, x, y, w, thumbH, MediaLibrary.aspect(e));
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
        // The metadata gets a column of its own on the left and the picture takes what is
        // left, rather than the panel being laid over the picture. A 3:2 frame in a 16:9
        // window already leaves margin on both sides, so on any normal window this costs the
        // image nothing — it only shifts where the letterboxing sits.
        java.util.List<String> exifLines = exifLines(e);
        int exifW = exifPanelWidth(exifLines);
        int imgLeft = PAD + (exifW > 0 ? exifW + EXIF_GAP : 0);
        if (tex != null) {
            drawFitted(ctx, tex, imgLeft, top, width - imgLeft - PAD, bottom - top,
                    MediaLibrary.aspect(e));
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

        drawExifPanel(ctx, exifLines, exifW, top, bottom);

        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("snapmatica.gallery.help_viewer"),
                width / 2, height - FOOTER_H + 3, CameraUi.CREAM_DIM);
    }

    /**
     * What the shot was taken at, read back out of the file itself rather than from the current
     * camera state — the settings have almost certainly moved since, and the point of the panel
     * is to say what THIS photograph used.
     *
     * <p>Empty for anything with no readable metadata (a video, or a PNG saved before this mod
     * wrote any), in which case no column is reserved and the picture uses the full width.
     */
    private java.util.List<String> exifLines(MediaLibrary.Entry e) {
        PhotoExif.Info info = MediaLibrary.exif(e);
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (info == null) return lines;
        if (info.exposure() != null) lines.add(info.exposure());
        if (info.lens() != null)     lines.add(info.lens());
        if (info.mode() != null)     lines.add(info.mode());
        if (info.taken() != null)    lines.add(info.taken());
        return lines;
    }

    /** Width the panel needs, or 0 when there is nothing to show. */
    private int exifPanelWidth(java.util.List<String> lines) {
        if (lines.isEmpty()) return 0;
        int w = 0;
        for (String l : lines) w = Math.max(w, textRenderer.getWidth(l));
        return w + 12;
    }

    /** Draws the panel in its reserved column, vertically centred against the picture. */
    private void drawExifPanel(DrawContext ctx, java.util.List<String> lines, int panelW,
                               int top, int bottom) {
        if (lines.isEmpty()) return;
        int lineH = textRenderer.fontHeight + 2;
        int panelH = lines.size() * lineH + 8;
        int px = PAD;
        int py = top + (bottom - top - panelH) / 2;

        ctx.fill(px, py, px + panelW, py + panelH, 0x50101014);
        // A hairline down the left edge, so the block reads as a label rather than as a
        // rectangle that happens to be sitting there.
        ctx.fill(px, py, px + 1, py + panelH, 0x80E8DCC4);

        int ty = py + 4;
        for (int i = 0; i < lines.size(); i++) {
            // The exposure triangle is the line anyone actually looks for, so it gets the
            // readable colour and the rest recede.
            ctx.drawTextWithShadow(textRenderer, Text.literal(lines.get(i)), px + 6, ty,
                    i == 0 ? 0xFFE8DCC4 : 0xFF9A9AA5);
            ty += lineH;
        }
    }

    /** Draws the texture centred and letterboxed inside the box, never stretched. */
    private void drawFitted(DrawContext ctx, Identifier tex, int bx, int by, int bw, int bh, float ar) {
        int dw = bw, dh = Math.round(bw / ar);
        if (dh > bh) { dh = bh; dw = Math.round(bh * ar); }
        int dx = bx + (bw - dw) / 2, dy = by + (bh - dh) / 2;
        // Three generations, three signatures: a RenderPipeline in 1.21.11, a RenderLayer
        // factory in 1.21.4, and neither in 1.21.1.
        //? if >=1.21.10 {
        ctx.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED,
                tex, dx, dy, 0f, 0f, dw, dh, dw, dh);
        //?} elif >=1.21.2 {
        /*ctx.drawTexture(net.minecraft.client.render.RenderLayer::getGuiTextured,
                tex, dx, dy, 0f, 0f, dw, dh, dw, dh);
        *///?} else {
        /*ctx.drawTexture(tex, dx, dy, 0f, 0f, dw, dh, dw, dh);
        *///?}
    }

    // ── Input ───────────────────────────────────────────────────────────────────

    /**
     * 1.21.11 replaced the loose (x, y, button) / (key, scancode, mods) parameters with Click
     * and KeyInput records, so each version gets its own thin wrapper over the shared logic.
     * 1.20.1 needs a third: it predates 1.21's separate horizontal/vertical scroll split, so
     * {@code mouseScrolled} there takes a single plain amount instead of (dx, dy).
     */
    //? if >=1.21.10 {
    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        return super.mouseClicked(click, doubled) || onClick(click.x(), click.y(), click.button());
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        return onScroll(dy) || super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.gui.Click click, double dx, double dy) {
        return onDrag(click.y()) || super.mouseDragged(click, dx, dy);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.gui.Click click) {
        return onRelease() || super.mouseReleased(click);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        return onKey(input.key()) || super.keyPressed(input);
    }
    //?} elif >=1.21 {
    /*@Override
    public boolean mouseClicked(double mx, double my, int button) {
        return super.mouseClicked(mx, my, button) || onClick(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        return onScroll(dy) || super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return onDrag(my) || super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        return onRelease() || super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scancode, int mods) {
        return onKey(key) || super.keyPressed(key, scancode, mods);
    }
    *///?} else {
    /*@Override
    public boolean mouseClicked(double mx, double my, int button) {
        return super.mouseClicked(mx, my, button) || onClick(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        return onScroll(amount) || super.mouseScrolled(mx, my, amount);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return onDrag(my) || super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        return onRelease() || super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scancode, int mods) {
        return onKey(key) || super.keyPressed(key, scancode, mods);
    }
    *///?}

    private boolean onClick(double mx, double my, int button) {
        // In the viewer everything worth doing is on a button, so a stray click does nothing.
        if (viewing >= 0) return false;
        if (my < HEADER_H || my >= height - FOOTER_H) return false;

        // The bar first: it sits over the last column of cells, and a click meant for it is
        // never meant for the picture underneath.
        int[] bar = barMetrics();
        if (bar != null && inBarColumn(mx)) {
            if (my >= bar[2] && my < bar[2] + bar[3]) {
                dragGrab = (int) my - bar[2];          // keep the grab point under the pointer
            } else {
                dragGrab = bar[3] / 2;                 // trough: centre the thumb where it landed
                scrollFromThumbTop((int) my - dragGrab);
            }
            draggingBar = true;
            return true;
        }

        int c = cols(), cw = cellW(), ch = cellH();
        for (int i = 0; i < entries.size(); i++) {
            int x = PAD + (i % c) * (cw + PAD);
            int y = HEADER_H + (i / c) * (ch + PAD) - scroll;
            if (mx >= x && mx < x + cw && my >= y && my < y + ch) {
                viewing = i;
                refreshActions();
                return true;
            }
        }
        return false;
    }

    private boolean onScroll(double dy) {
        if (viewing >= 0) { step(dy > 0 ? -1 : 1); return true; }
        scroll -= (int) (dy * 40);
        clampScroll();
        return true;
    }

    private boolean onDrag(double my) {
        if (!draggingBar) return false;
        scrollFromThumbTop((int) my - dragGrab);
        return true;
    }

    private boolean onRelease() {
        if (!draggingBar) return false;
        draggingBar = false;
        return true;
    }

    private boolean onKey(int key) {
        if (viewing < 0) return false;
        switch (key) {
            case 263 -> { step(-1); return true; }                        // left
            case 262 -> { step(1);  return true; }                        // right
            case 257, 335 -> { open(entries.get(viewing)); return true; } // enter
            case 256 -> { leaveViewer(); return true; }                  // esc: back to grid
            default -> { return false; }
        }
    }

    private void step(int d) {
        if (entries.isEmpty()) return;
        viewing = Math.floorMod(viewing + d, entries.size());
        deleteArmed = false;
        refreshActions();
    }

    private void open(MediaLibrary.Entry e) {
        MediaLibrary.openExternally(e.file());
    }
}
