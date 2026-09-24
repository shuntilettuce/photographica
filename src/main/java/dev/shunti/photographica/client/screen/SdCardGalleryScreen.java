package dev.hitom.photographica.client.screen;

import dev.hitom.photographica.client.ClipboardUtil;
import dev.hitom.photographica.client.render.PhotoTextureCache;
import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.component.PhotoData;
import dev.hitom.photographica.component.SdCardData;
import dev.hitom.photographica.network.DeleteSdPhotoPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SD card photo gallery: a thumbnail grid (tap a cell to open it full-screen) rather than the
 * old page-by-page {@code SdCardBrowserScreen} it replaces. Reachable both directly from
 * {@link dev.hitom.photographica.item.SdCardItem#use} (right-click the card itself) and from
 * the camera settings screen's "SDカード" button.
 *
 * <p>Thumbnails go through {@link PhotoTextureCache}, so a photo someone else took is fetched
 * from the server transparently the same way the full-screen viewer already does.
 */
@Environment(EnvType.CLIENT)
public class SdCardGalleryScreen extends Screen {
    private static final int PAD = 8;
    private static final int CELL_TARGET_W = 108; // thumbnail + caption cell target width
    private static final int CELL_H = 92;         // 3:2 thumb (72px tall) + caption strip
    private static final int THUMB_H = 64;
    private static final int DELETE_CONFIRM_TIMEOUT_MS = 4000;

    private final ItemStack cameraStack;
    private final Screen parent;
    private final List<PhotoData> photos;

    /** -1 = grid; otherwise index into {@link #photos} being shown full-screen. */
    private int viewing = -1;
    private int scrollY = 0;
    private int cols = 1;
    private long deleteArmedAtMs = 0;

    public SdCardGalleryScreen(ItemStack cameraStack, SdCardData sdData, Screen parent) {
        super(Text.literal("SD CARD"));
        this.cameraStack = cameraStack;
        this.parent = parent;
        this.photos = new ArrayList<>(sdData.photos());
    }

    @Override
    protected void init() {
        cols = Math.max(1, (width - PAD) / (CELL_TARGET_W + PAD));

        if (viewing < 0) {
            addDrawableChild(SafelightButton.ghost(PAD, height - 24, 80,
                    Text.literal("← 戻る"), b -> close()));
            return;
        }

        int btnY = height - 24;
        int cx = width / 2;
        addDrawableChild(SafelightButton.ghost(8, btnY, 70,
                Text.literal("◀ 一覧"), b -> { viewing = -1; deleteArmedAtMs = 0; clearAndInit(); }));
        addDrawableChild(SafelightButton.of(cx - 110, btnY, 70,
                Text.literal("◀ PREV"), b -> navigate(-1)));
        addDrawableChild(SafelightButton.of(cx - 36, btnY, 72,
                Text.literal("📋 コピー"), b -> copyCurrentPhoto()));
        addDrawableChild(SafelightButton.of(cx + 40, btnY, 70,
                Text.literal("NEXT ▶"), b -> navigate(1)));
        addDrawableChild(SafelightButton.of(width - 8 - 70, btnY,
                70, deleteButtonLabel(), b -> deleteCurrentPhoto()));
    }

    private Text deleteButtonLabel() {
        boolean armed = System.currentTimeMillis() < deleteArmedAtMs;
        return Text.literal(armed ? "確認?" : "削除");
    }

    private void navigate(int dir) {
        if (photos.isEmpty()) return;
        viewing = Math.max(0, Math.min(photos.size() - 1, viewing + dir));
        deleteArmedAtMs = 0;
        clearAndInit();
    }

    // -------------------------------------------------------------------------
    // Render
    // -------------------------------------------------------------------------

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {}

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xFF101010);

        if (viewing < 0) {
            renderGrid(ctx, mouseX, mouseY);
        } else {
            renderDetail(ctx);
        }

        super.render(ctx, mouseX, mouseY, delta);
    }

    private void renderGrid(DrawContext ctx, int mouseX, int mouseY) {
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("SD CARD (" + photos.size() + ")"), width / 2, 8, GuiHelper.CREAM);

        if (photos.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("NO PHOTOS"), width / 2, height / 2 - 5, GuiHelper.CREAM_FAINT);
            return;
        }

        int top = 24;
        int bottom = height - 32;
        int cellW = (width - PAD) / cols;

        ctx.enableScissor(0, top, width, bottom);
        for (int i = 0; i < photos.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            int cx = PAD + col * cellW;
            int cy = top + row * CELL_H - scrollY;
            if (cy + CELL_H < top || cy > bottom) continue;
            renderCell(ctx, photos.get(i), cx, cy, cellW - PAD, mouseX, mouseY);
        }
        ctx.disableScissor();
    }

    private void renderCell(DrawContext ctx, PhotoData photo, int x, int y, int w, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + THUMB_H;
        ctx.fill(x, y, x + w, y + THUMB_H, hovered ? GuiHelper.PANEL_LIGHT : GuiHelper.PANEL_2);

        Identifier tex = PhotoTextureCache.getOrLoad(photo.id());
        if (tex != null) {
            int[] sz = PhotoTextureCache.getSize(photo.id());
            int texW = sz != null ? sz[0] : 1;
            int texH = sz != null ? sz[1] : 1;
            float aspect = (float) texW / texH;
            int dw = w, dh = Math.round(w / aspect);
            if (dh > THUMB_H) { dh = THUMB_H; dw = Math.round(THUMB_H * aspect); }
            int dx = x + (w - dw) / 2;
            int dy = y + (THUMB_H - dh) / 2;
            drawPhotoTexture(ctx, tex, dx, dy, dw, dh, texW, texH);
        } else {
            String label = PhotoTextureCache.isFetching(photo.id()) ? "…" : "⚠";
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(label),
                    x + w / 2, y + THUMB_H / 2 - 4, GuiHelper.CREAM_FAINT);
        }

        String caption = photo.photographer();
        int capW = textRenderer.getWidth(caption);
        if (capW > w) caption = textRenderer.trimToWidth(caption, w - 6) + "…";
        ctx.drawText(textRenderer, Text.literal(caption), x + 2, y + THUMB_H + 3, GuiHelper.CREAM_DIM, false);
    }

    private void renderDetail(DrawContext ctx) {
        PhotoData p = photos.get(viewing);
        Identifier tex = PhotoTextureCache.getOrLoad(p.id());

        if (tex != null) {
            int[] sz = PhotoTextureCache.getSize(p.id());
            int texW = sz != null ? sz[0] : 1;
            int texH = sz != null ? sz[1] : 1;
            float aspect = (float) texW / texH;
            int maxW = Math.max(16, (int) (width * 0.9f));
            int maxH = Math.max(16, (int) (height * 0.7f));
            int dw, dh;
            if (maxW / aspect <= maxH) { dw = maxW; dh = Math.max(1, Math.round(maxW / aspect)); }
            else { dh = maxH; dw = Math.max(1, Math.round(maxH * aspect)); }
            int dx = (width - dw) / 2;
            int dy = (height - dh) / 2 - 12;

            ctx.fill(dx - 2, dy - 2, dx + dw + 2, dy + dh + 2, 0xFFFFFFFF);
            ctx.fill(dx - 1, dy - 1, dx + dw + 1, dy + dh + 1, 0xFF000000);
            drawPhotoTexture(ctx, tex, dx, dy, dw, dh, texW, texH);

            if (p.fogged()) {
                ctx.fill(dx, dy, dx + dw, dy + dh, 0xC8FFFFFF);
                ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("§c光被り"),
                        dx + dw / 2, dy + dh / 2 - 4, 0xFFFF4444);
            }
        } else if (PhotoTextureCache.isFetching(p.id())) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("[ 読み込み中… ]"), width / 2, height / 2 - 6, 0xFFCCCCCC);
        } else {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("[ 写真ファイルが見つかりません ]"), width / 2, height / 2 - 6, 0xFFFF5555);
        }

        String counter = (viewing + 1) + " / " + photos.size();
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(counter), width / 2, 6, GuiHelper.CREAM);

        String exposure = String.format("F%.1f  ISO%d  %dmm",
                p.cameraAtCapture().aperture(), p.cameraAtCapture().iso(), p.cameraAtCapture().focalLengthMm());
        String dim = p.dimension();
        int colon = dim.lastIndexOf(':');
        if (colon >= 0) dim = dim.substring(colon + 1);
        String location = String.format("%s (%d, %d, %d)", dim, p.x(), p.y(), p.z());

        ctx.drawTextWithShadow(textRenderer, Text.literal("撮影者: " + p.photographer()), 8, height - 44, GuiHelper.CREAM_DIM);
        ctx.drawTextWithShadow(textRenderer, Text.literal(exposure), 8, height - 34, 0xFFB0B0B0);
        int locW = textRenderer.getWidth(location);
        ctx.drawTextWithShadow(textRenderer, Text.literal(location), width - 8 - locW, height - 34, 0xFF808080);
    }

    private static void drawPhotoTexture(DrawContext ctx, Identifier tex, int x, int y, int w, int h, int texW, int texH) {
        //? if >=1.21.11 {
        /*ctx.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, tex, x, y, 0f, 0f, w, h, texW, texH, texW, texH);*/
        //?} else {
        //? if >=1.21.4 {
        /*ctx.drawTexture(net.minecraft.client.render.RenderLayer::getGuiTextured, tex, x, y, 0f, 0f, w, h, texW, texH, texW, texH);*/
        //?} else {
        ctx.drawTexture(tex, x, y, w, h, 0f, 0f, texW, texH, texW, texH);
        //?}
        //?}
    }

    // -------------------------------------------------------------------------
    // Input
    // -------------------------------------------------------------------------

    /**
     * 1.21.11 replaced the loose (x, y, button) mouse-click parameters with a {@code Click}
     * record, so each version gets its own thin wrapper over this shared logic.
     */
    //? if >=1.21.10 {
    /*@Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        return super.mouseClicked(click, doubled) || onClick(click.x(), click.y(), click.button());
    }
    *///?} else {
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return super.mouseClicked(mouseX, mouseY, button) || onClick(mouseX, mouseY, button);
    }
    //?}

    private boolean onClick(double mouseX, double mouseY, int button) {
        if (viewing < 0 && button == 0 && !photos.isEmpty()) {
            int top = 24;
            int cellW = (width - PAD) / cols;
            int col = (int) ((mouseX - PAD) / cellW);
            int row = (int) ((mouseY - top + scrollY) / CELL_H);
            if (col >= 0 && col < cols && mouseY >= top) {
                int idx = row * cols + col;
                double localY = (mouseY - top + scrollY) - row * CELL_H;
                if (idx >= 0 && idx < photos.size() && localY < THUMB_H) {
                    viewing = idx;
                    deleteArmedAtMs = 0;
                    clearAndInit();
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (viewing < 0) {
            int rows = (photos.size() + cols - 1) / cols;
            int contentH = rows * CELL_H;
            int visibleH = height - 24 - 32;
            int maxScroll = Math.max(0, contentH - visibleH);
            scrollY = MathHelper.clamp((int) (scrollY - verticalAmount * CELL_H / 2), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    private void copyCurrentPhoto() {
        if (photos.isEmpty()) return;
        PhotoData p = photos.get(viewing);
        MinecraftClient mc = MinecraftClient.getInstance();
        File file = new File(mc.runDirectory, "photographica/photos/" + p.id() + ".jpg");
        if (!file.isFile()) {
            mc.inGameHud.setOverlayMessage(Text.literal("⚠ 写真ファイルが見つかりません"), false);
            return;
        }
        ClipboardUtil.copyImageAsync(file);
    }

    /** Two-press confirm, same pattern as snapmatica's gallery — arm on first press, delete on
     *  a second press within {@link #DELETE_CONFIRM_TIMEOUT_MS}, auto-disarm on timeout. */
    private void deleteCurrentPhoto() {
        if (photos.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now >= deleteArmedAtMs) {
            deleteArmedAtMs = now + DELETE_CONFIRM_TIMEOUT_MS;
            clearAndInit();
            return;
        }
        deleteArmedAtMs = 0;

        PhotoData p = photos.get(viewing);
        UUID photoId = p.id();

        MinecraftClient mc = MinecraftClient.getInstance();
        new File(mc.runDirectory, "photographica/photos/" + photoId + ".jpg").delete();

        ClientPlayNetworking.send(new DeleteSdPhotoPayload(photoId));

        SdCardData current = cameraStack.getOrDefault(ModDataComponents.SD_CARD, SdCardData.EMPTY);
        cameraStack.set(ModDataComponents.SD_CARD, current.withoutPhoto(photoId));

        photos.remove(viewing);
        if (photos.isEmpty()) {
            viewing = -1;
        } else {
            viewing = Math.max(0, Math.min(photos.size() - 1, viewing));
        }
        clearAndInit();
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
