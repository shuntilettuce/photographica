package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * The camera roll: a scrolling grid of everything shot, and a full-screen viewer.
 *
 * <p>Laid out like a phone gallery rather than a file list — newest first, click to open, arrow
 * keys or the wheel to move through. Videos cannot play inside Minecraft, so they show their
 * poster frame with a play badge and open in the desktop player.
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
    private CameraUi.SnapButton copyBtn, revealBtn, openBtn, deleteBtn, backBtn, colsBtn;
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
        super(Component.literal("Gallery"));
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

        copyBtn = CameraUi.SnapButton.of(x, by, wCopy,
                Component.translatable("snapmatica.gallery.copy"),
                b -> { if (viewing >= 0) MediaLibrary.copyToClipboard(entries.get(viewing)); });
        x += wCopy + gap;
        revealBtn = CameraUi.SnapButton.of(x, by, wReveal,
                Component.translatable("snapmatica.gallery.reveal"),
                b -> { if (viewing >= 0) MediaLibrary.revealInFolder(entries.get(viewing).file()); });
        x += wReveal + gap;
        openBtn = CameraUi.SnapButton.primary(x, by, wOpen,
                Component.translatable("snapmatica.gallery.open"),
                b -> { if (viewing >= 0) open(entries.get(viewing)); });
        x += wOpen + gap;
        deleteBtn = CameraUi.SnapButton.ghost(x, by, wDelete,
                Component.translatable("snapmatica.gallery.delete"),
                b -> onDeletePressed());
        x += wDelete + gap;
        backViewerX = x;
        backGridX = (width - wBack) / 2;
        backBtn = CameraUi.SnapButton.ghost(x, by, wBack,
                Component.translatable("snapmatica.common.close"),
                b -> { if (viewing >= 0) { leaveViewer(); } else onClose(); });

        colsBtn = CameraUi.SnapButton.ghost(PAD, by, 78,
                Component.translatable("snapmatica.gallery.cols", colsLabel()),
                b -> cycleCols(1));

        addRenderableWidget(colsBtn);
        addRenderableWidget(copyBtn);
        addRenderableWidget(revealBtn);
        addRenderableWidget(openBtn);
        addRenderableWidget(deleteBtn);
        addRenderableWidget(backBtn);
        refreshActions();
    }

    /** Keeps the action row in step with what is on screen. */
    private void refreshActions() {
        boolean viewer = viewing >= 0;
        if (colsBtn != null) {
            colsBtn.visible = !viewer && !entries.isEmpty();
            colsBtn.setMessage(Component.translatable("snapmatica.gallery.cols", colsLabel()));
        }
        copyBtn.visible = revealBtn.visible = openBtn.visible = deleteBtn.visible = viewer;
        backBtn.setX(viewer ? backViewerX : backGridX);
        backBtn.setMessage(Component.translatable(viewer ? "snapmatica.common.back"
                                                         : "snapmatica.common.close"));
        if (viewer) {
            openBtn.setMessage(Component.translatable(entries.get(viewing).video()
                    ? "snapmatica.gallery.play" : "snapmatica.gallery.open"));
            deleteBtn.setMessage(Component.translatable(deleteArmed
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
    public boolean isPauseScreen() { return false; }

    // ── Layout ──────────────────────────────────────────────────────────────────

    /** Below this a cell stops being a picture and starts being a smear. */
    private static final int MIN_CELL = 54;

    /** The most columns this window can hold without the cells collapsing. */
    private int maxCols() { return Math.max(1, (width - PAD) / (MIN_CELL + PAD)); }

    private int cols() {
        int fixed = SnapmaticaClient.galleryCols;
        int auto = Math.max(1, (width - PAD) / (COLS_TARGET_CELL + PAD));
        // A chosen density is a request, not a promise: a narrow window cannot show ten
        // pictures across without each of them being a few pixels wide, so the window still
        // has the last word.
        return Math.min(fixed > 0 ? fixed : auto, maxCols());
    }

    /** Step the density on, wrapping. Keeps the top-left picture in view. */
    private void cycleCols(int dir) {
        int cur = 0;
        for (int i = 0; i < COL_CHOICES.length; i++) {
            if (COL_CHOICES[i] == SnapmaticaClient.galleryCols) { cur = i; break; }
        }
        // Clamped, not wrapped. Running off the dense end and reappearing at the sparse one is
        // not a density control, it is a surprise.
        int next = Math.max(0, Math.min(COL_CHOICES.length - 1, cur + dir));
        SnapmaticaClient.galleryCols = COL_CHOICES[next];
        SnapmaticaConfig.save();
        // The row the top of the viewport was showing, kept across the change, so the roll does
        // not jump somewhere else the moment the density changes.
        clampScroll();
        refreshActions();
    }

    private String colsLabel() {
        return SnapmaticaClient.galleryCols > 0
                ? String.valueOf(SnapmaticaClient.galleryCols)
                : Component.translatable("snapmatica.gallery.cols_auto").getString();
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
     * Deliberately empty; the backdrop is painted from {@link #extractRenderState} so its order
     * relative to this screen's own content is never in doubt.
     */
    @Override
    public void extractBackground(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {}

    /** The dimmed backdrop this screen sits on. */
    private void drawBackdrop(GuiGraphicsExtractor ctx) {
        ctx.fill(0, 0, width, height, 0xF00E0E10);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        if (deleteArmed && System.currentTimeMillis() - deleteArmedAt > DELETE_CONFIRM_TIMEOUT_MS) {
            deleteArmed = false;
            refreshActions();
        }
        drawBackdrop(ctx);
        if (viewing >= 0) renderViewer(ctx);
        else              renderGrid(ctx, mouseX, mouseY);
        super.extractRenderState(ctx, mouseX, mouseY, delta);
    }

    private void renderGrid(GuiGraphicsExtractor ctx, int mouseX, int mouseY) {
        ctx.text(font, Component.translatable("snapmatica.gallery.title"), PAD + 2, 10, CameraUi.CREAM);
        String count = Component.translatable("snapmatica.gallery.items", entries.size()).getString();
        ctx.text(font, count, width - PAD - font.width(count) - 2, 10, 0xFF7A7A85);

        // A couple of finished thumbnails onto the GPU, and no more: the decode already
        // happened on a worker, and this is the only part that has to be here.
        MediaLibrary.pumpThumbnails();

        if (entries.isEmpty()) {
            ctx.centeredText(font, Component.translatable("snapmatica.gallery.empty"),
                    width / 2, height / 2, 0xFF7A7A85);
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

        ctx.centeredText(font, Component.translatable("snapmatica.gallery.help_grid"),
                width / 2, height - FOOTER_H + 3, CameraUi.CREAM_DIM);
    }

    private void drawCell(GuiGraphicsExtractor ctx, MediaLibrary.Entry e,
                          int x, int y, int w, int h, boolean hover) {
        int thumbH = w * 2 / 3;
        ctx.fill(x, y, x + w, y + thumbH, hover ? 0xFF2A2A32 : 0xFF1A1A20);

        Identifier tex = MediaLibrary.thumbnail(e);
        if (tex != null) {
            drawFitted(ctx, tex, x, y, w, thumbH, MediaLibrary.aspect(e));
        } else {
            ctx.centeredText(font,
                    e.video() ? Component.translatable("snapmatica.gallery.video")
                              : Component.literal("..."),
                    x + w / 2, y + thumbH / 2 - 4, 0xFF55555F);
        }

        if (e.video()) {
            // Play badge, so a still and a clip are never confused at a glance.
            int bx = x + w - 16, by = y + thumbH - 14;
            ctx.fill(bx - 2, by - 2, bx + 12, by + 10, 0xB0000000);
            ctx.text(font, Component.literal("▶"), bx, by, 0xFFFFFFFF);
        }

        if (hover) {
            ctx.fill(x, y, x + w, y + 1, CameraUi.CREAM);
            ctx.fill(x, y + thumbH - 1, x + w, y + thumbH, CameraUi.CREAM);
            ctx.fill(x, y, x + 1, y + thumbH, CameraUi.CREAM);
            ctx.fill(x + w - 1, y, x + w, y + thumbH, CameraUi.CREAM);
        }

        ctx.text(font, STAMP.format(new Date(e.modified())), x + 1, y + thumbH + 3, 0xFF8A8A95);
    }

    private void renderViewer(GuiGraphicsExtractor ctx) {
        MediaLibrary.Entry e = entries.get(viewing);
        Identifier tex = MediaLibrary.texture(e);

        int top = HEADER_H, bottom = height - FOOTER_H;
        if (tex != null) {
            drawFitted(ctx, tex, PAD, top, width - PAD * 2, bottom - top, MediaLibrary.aspect(e));
        } else {
            ctx.centeredText(font,
                    Component.translatable(e.video() ? "snapmatica.gallery.preparing"
                                                     : "snapmatica.gallery.unreadable"),
                    width / 2, height / 2, 0xFF7A7A85);
        }

        ctx.text(font, e.displayName(), PAD + 2, 10, CameraUi.CREAM);
        String pos = (viewing + 1) + " / " + entries.size();
        ctx.text(font, pos, width - PAD - font.width(pos) - 2, 10, 0xFF7A7A85);

        ctx.centeredText(font, Component.translatable("snapmatica.gallery.help_viewer"),
                width / 2, height - FOOTER_H + 3, CameraUi.CREAM_DIM);
    }

    /** Draws the texture centred and letterboxed inside the box, never stretched. */
    private void drawFitted(GuiGraphicsExtractor ctx, Identifier tex,
                            int bx, int by, int bw, int bh, float ar) {
        int dw = bw, dh = Math.round(bw / ar);
        if (dh > bh) { dh = bh; dw = Math.round(bh * ar); }
        int dx = bx + (bw - dw) / 2, dy = by + (bh - dh) / 2;
        ctx.blit(RenderPipelines.GUI_TEXTURED, tex, dx, dy, 0f, 0f, dw, dh, dw, dh);
    }

    // ── Input ───────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        // Widgets get first refusal — the action row is real, so it must win over the grid's
        // own hit test.
        return super.mouseClicked(event, doubled) || onClick(event.x(), event.y());
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        return onScroll(dy) || super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        return onDrag(event.y()) || super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return onRelease() || super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return onKey(event.key()) || super.keyPressed(event);
    }

    private boolean onClick(double mx, double my) {
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
        // Ctrl+wheel changes the density. A camera does this in playback with the zoom lever --
        // one picture, four, nine, thirty-six -- and it is the same gesture on the same control
        // that moves through them, which is why it belongs on the wheel and not only on a
        // button. The modifier is read through CameraScrollHandler because Screen.hasControlDown
        // is gone at 1.21.11 and that class already owns the version branch for asking GLFW.
        if (CameraScrollHandler.ctrlDown()) { cycleCols(dy > 0 ? -1 : 1); return true; }
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
            case 256 -> { leaveViewer(); return true; }  // esc: back to grid
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
