package dev.hitom.photographica.client.screen;

import dev.hitom.photographica.Photographica;
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
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Playback screen for browsing photos stored on an SD card inside a camera.
 * Shows a thumbnail + metadata. Prev/Next to cycle; "全画面" opens PhotoViewerScreen.
 */
@Environment(EnvType.CLIENT)
public class SdCardBrowserScreen extends Screen {

    private record ThumbImage(Identifier id, int texW, int texH, int guiW, int guiH) {}

    private static final String[] SHUTTERS = {
            "30\"", "15\"", "8\"", "4\"", "2\"", "1\"",
            "1/2", "1/4", "1/8", "1/15", "1/30", "1/60",
            "1/125", "1/250", "1/500", "1/1000", "1/2000", "1/4000"
    };

    // Panel dimensions
    private static final int PANEL_W = 320;
    private static final int PANEL_H = 264;
    // Thumbnail display constraints (in GUI pixels)
    private static final int THUMB_MAX_W = 288;
    private static final int THUMB_MAX_H = 144;

    private final ItemStack cameraStack;
    private final Screen parent;
    private final List<PhotoData> photos;  // mutable local copy
    private int index = 0;

    private ThumbImage thumb = null;
    private boolean thumbMissing = false;
    private int loadedForIndex = -1;

    public SdCardBrowserScreen(ItemStack cameraStack, SdCardData sdData, Screen parent) {
        super(Text.literal("SD CARD"));
        this.cameraStack = cameraStack;
        this.parent = parent;
        this.photos = new ArrayList<>(sdData.photos());
    }

    @Override
    protected void init() {
        if (!photos.isEmpty() && loadedForIndex != index) {
            loadThumb(photos.get(index));
        }

        int cx = width / 2;
        int py = (height - PANEL_H) / 2;

        if (photos.isEmpty()) {
            addDrawableChild(SafelightButton.ghost(cx - 50, py + PANEL_H - 28, 100,
                    Text.literal("← 戻る"), b -> close()));
            return;
        }

        // Prev / Next
        int navY = py + PANEL_H - 56;
        addDrawableChild(SafelightButton.of(cx - 105, navY, 100,
                Text.literal("◀ PREV"), b -> navigate(-1)));
        addDrawableChild(SafelightButton.of(cx + 5, navY, 100,
                Text.literal("NEXT ▶"), b -> navigate(1)));

        // Full-screen view / delete / back  (3 buttons, each 64px wide)
        int btnY = py + PANEL_H - 28;
        addDrawableChild(SafelightButton.primary(cx - 99, btnY, 64,
                Text.literal("全画面"),
                b -> client.setScreen(new PhotoViewerScreen(photos.get(index), this))));
        addDrawableChild(SafelightButton.of(cx - 32, btnY, 64,
                Text.literal("削除"),
                b -> deleteCurrentPhoto()));
        addDrawableChild(SafelightButton.ghost(cx + 35, btnY, 64,
                Text.literal("← 戻る"), b -> close()));
    }

    private void navigate(int dir) {
        index = Math.max(0, Math.min(photos.size() - 1, index + dir));
        clearAndInit();
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {}

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0xFF101010);

        int cx = width / 2;
        int px = cx - PANEL_W / 2;
        int py = (height - PANEL_H) / 2;

        GuiHelper.drawPanel(ctx, px, py, PANEL_W, PANEL_H);
        GuiHelper.drawNameplate(ctx, px + 6, py + 5, PANEL_W - 12);
        GuiHelper.drawRule(ctx, px + 6, py + 17, PANEL_W - 12);
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("SD CARD"), cx, py + 6, GuiHelper.CREAM);

        if (photos.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("NO PHOTOS"), cx, py + PANEL_H / 2 - 5, GuiHelper.CREAM_FAINT);
            super.render(ctx, mouseX, mouseY, delta);
            return;
        }

        // Thumbnail area: starts at py+22, max height THUMB_MAX_H
        int thumbAreaTop = py + 22;
        if (thumbMissing) {
            ctx.fill(cx - THUMB_MAX_W / 2, thumbAreaTop,
                    cx + THUMB_MAX_W / 2, thumbAreaTop + THUMB_MAX_H, 0xFF1A1510);
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("[ NO FILE ]"),
                    cx, thumbAreaTop + THUMB_MAX_H / 2 - 4, GuiHelper.CREAM_FAINT);
        } else if (thumb != null) {
            int tx = cx - thumb.guiW() / 2;
            int ty = thumbAreaTop + (THUMB_MAX_H - thumb.guiH()) / 2;
            // Thin frame around thumbnail
            ctx.fill(tx - 1, ty - 1, tx + thumb.guiW() + 1, ty + thumb.guiH() + 1, 0xFF9B6F30);
            //? if >=1.21.11 {
            /*ctx.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, thumb.id(), tx, ty, 0f, 0f,
                    thumb.guiW(), thumb.guiH(), thumb.texW(), thumb.texH(), thumb.texW(), thumb.texH());*/
            //?} else if >=1.21.4 {
            /*ctx.drawTexture(net.minecraft.client.render.RenderLayer::getGuiTextured, thumb.id(), tx, ty, 0f, 0f,
                    thumb.guiW(), thumb.guiH(), thumb.texW(), thumb.texH(), thumb.texW(), thumb.texH());*/
            //?} else {
            ctx.drawTexture(thumb.id(), tx, ty, thumb.guiW(), thumb.guiH(),
                    0f, 0f, thumb.texW(), thumb.texH(), thumb.texW(), thumb.texH());
            //?}
        }

        // Metadata block below thumbnail
        PhotoData p = photos.get(index);
        int metaY = py + 22 + THUMB_MAX_H + 4;
        int lineH = textRenderer.fontHeight + 2;

        // Index counter
        String counter = (index + 1) + " / " + photos.size();
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(counter), cx, metaY, GuiHelper.CREAM);
        metaY += lineH;

        String exposure = String.format("F%.1f  %s  ISO%d  %dmm",
                p.cameraAtCapture().aperture(),
                shutterLabel(p.cameraAtCapture().shutterSpeedIdx()),
                p.cameraAtCapture().iso(),
                p.cameraAtCapture().focalLengthMm());
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(exposure), cx, metaY, GuiHelper.BRASS_BRIGHT);
        metaY += lineH;

        String loc = shortDim(p.dimension()) + "  (" + p.x() + ", " + p.y() + ", " + p.z() + ")";
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(loc), cx, metaY, GuiHelper.CREAM_DIM);

        if (p.fogged()) {
            metaY += lineH;
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("⚠ 光被り"), cx, metaY, GuiHelper.EMBER);
        }

        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    private void deleteCurrentPhoto() {
        if (photos.isEmpty()) return;
        PhotoData p = photos.get(index);
        UUID photoId = p.id();

        // Delete PNG from disk
        MinecraftClient mc = MinecraftClient.getInstance();
        File file = new File(mc.runDirectory, "photographica/photos/" + photoId + ".png");
        file.delete();

        // Release cached thumbnail texture
        if (thumb != null && loadedForIndex == index) {
            mc.getTextureManager().destroyTexture(thumb.id());
            thumb = null;
            loadedForIndex = -1;
        }

        // Notify server
        ClientPlayNetworking.send(new DeleteSdPhotoPayload(photoId));

        // Optimistic client-side update so CameraScreen reads fresh data on return
        SdCardData current = cameraStack.getOrDefault(ModDataComponents.SD_CARD, SdCardData.EMPTY);
        cameraStack.set(ModDataComponents.SD_CARD, current.withoutPhoto(photoId));

        // Update local list and clamp index
        photos.remove(index);
        index = Math.max(0, Math.min(photos.size() - 1, index));

        clearAndInit();
    }

    // -------------------------------------------------------------------------
    // Thumbnail loading
    // -------------------------------------------------------------------------

    private void loadThumb(PhotoData data) {
        thumb = null;
        thumbMissing = false;
        loadedForIndex = index;

        MinecraftClient mc = MinecraftClient.getInstance();
        File file = new File(mc.runDirectory, "photographica/photos/" + data.id() + ".png");
        if (!file.isFile()) {
            thumbMissing = true;
            return;
        }

        NativeImage original = null;
        NativeImage forTexture = null;
        try (FileInputStream fis = new FileInputStream(file)) {
            original = NativeImage.read(fis);

            float aspect = (float) original.getWidth() / original.getHeight();
            int guiW, guiH;
            if (THUMB_MAX_W / aspect <= THUMB_MAX_H) {
                guiW = THUMB_MAX_W;
                guiH = Math.max(1, (int) (THUMB_MAX_W / aspect));
            } else {
                guiH = THUMB_MAX_H;
                guiW = Math.max(1, (int) (THUMB_MAX_H * aspect));
            }

            double sf = mc.getWindow().getScaleFactor();
            int physW = Math.max(1, (int) Math.round(guiW * sf));
            int physH = Math.max(1, (int) Math.round(guiH * sf));

            if (physW >= original.getWidth()) {
                forTexture = original;
                original = null;
                physW = forTexture.getWidth();
                physH = forTexture.getHeight();
            } else {
                forTexture = boxResample(original, physW, physH);
            }

            String safeId = data.id().toString().replace('-', '_').toLowerCase();
            Identifier texId = Identifier.of(Photographica.MOD_ID, "thumb/" + safeId);
            //? if >=1.21.11 {
            /*NativeImageBackedTexture tex = new NativeImageBackedTexture(() -> "thumb/" + safeId, forTexture);*/
            //?} else {
            NativeImageBackedTexture tex = new NativeImageBackedTexture(forTexture);
            //?}
            //? if <1.21.11 {
            tex.setFilter(true, false);
            //?}
            mc.getTextureManager().registerTexture(texId, tex);
            forTexture = null;

            thumb = new ThumbImage(texId, physW, physH, guiW, guiH);
        } catch (IOException e) {
            Photographica.LOGGER.error("Failed to load thumbnail {}", data.id(), e);
            thumbMissing = true;
        } finally {
            if (forTexture != null) forTexture.close();
            if (original != null) original.close();
        }
    }

    private static NativeImage boxResample(NativeImage src, int dw, int dh) {
        int sw = src.getWidth(), sh = src.getHeight();
        NativeImage dst = new NativeImage(dw, dh, false);
        float xScale = (float) sw / dw;
        float yScale = (float) sh / dh;
        for (int y = 0; y < dh; y++) {
            int sy0 = (int) Math.floor(y * yScale);
            int sy1 = Math.min(sh, (int) Math.ceil((y + 1) * yScale));
            if (sy1 <= sy0) sy1 = sy0 + 1;
            for (int x = 0; x < dw; x++) {
                int sx0 = (int) Math.floor(x * xScale);
                int sx1 = Math.min(sw, (int) Math.ceil((x + 1) * xScale));
                if (sx1 <= sx0) sx1 = sx0 + 1;
                long ra = 0, ga = 0, ba = 0, aa = 0;
                int n = 0;
                for (int sy = sy0; sy < sy1; sy++)
                    for (int sx = sx0; sx < sx1; sx++) {
                        int c = getPixelAbgr(src, sx, sy);
                        aa += (c >>> 24) & 0xFF; ba += (c >>> 16) & 0xFF;
                        ga += (c >>>  8) & 0xFF; ra +=  c         & 0xFF;
                        n++;
                    }
                setPixelAbgr(dst, x, y, (((int)(aa/n))<<24)|(((int)(ba/n))<<16)
                        |(((int)(ga/n))<<8)|((int)(ra/n)));
            }
        }
        return dst;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String shutterLabel(int idx) {
        if (idx < 0) return SHUTTERS[0];
        if (idx >= SHUTTERS.length) return SHUTTERS[SHUTTERS.length - 1];
        return SHUTTERS[idx];
    }

    private static String shortDim(String dim) {
        if (dim == null) return "?";
        int colon = dim.lastIndexOf(':');
        return colon >= 0 ? dim.substring(colon + 1) : dim;
    }

    //? if >=1.21.4 {
    /*private static int getPixelAbgr(net.minecraft.client.texture.NativeImage img, int x, int y) {
        int argb = img.getColorArgb(x, y);
        int a=(argb>>>24)&0xFF; int r=(argb>>>16)&0xFF; int g=(argb>>>8)&0xFF; int b=argb&0xFF;
        return (a<<24)|(b<<16)|(g<<8)|r;
    }
    private static void setPixelAbgr(net.minecraft.client.texture.NativeImage img, int x, int y, int abgr) {
        int a=(abgr>>>24)&0xFF; int b=(abgr>>>16)&0xFF; int g=(abgr>>>8)&0xFF; int r=abgr&0xFF;
        img.setColorArgb(x, y, (a<<24)|(r<<16)|(g<<8)|b);
    }*/
    //?} else {
    private static int getPixelAbgr(net.minecraft.client.texture.NativeImage img, int x, int y) { return img.getColor(x, y); }
    private static void setPixelAbgr(net.minecraft.client.texture.NativeImage img, int x, int y, int abgr) { img.setColor(x, y, abgr); }
    //?}
}
