package dev.hitom.photographica.client.screen;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.component.FilmKind;
import dev.hitom.photographica.component.FilmRollData;
import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.component.PhotoData;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

/**
 * Preview screen for a developed film roll. Thumbnails are displayed as negatives
 * (inverted colours) to simulate holding a strip up to a light box.
 */
@Environment(EnvType.CLIENT)
public class FilmStripScreen extends Screen {

    private record ThumbImage(Identifier id, int texW, int texH, int guiW, int guiH) {}

    private static final String[] SHUTTERS = {
            "30\"", "15\"", "8\"", "4\"", "2\"", "1\"",
            "1/2", "1/4", "1/8", "1/15", "1/30", "1/60",
            "1/125", "1/250", "1/500", "1/1000", "1/2000", "1/4000"
    };

    private static final int PANEL_W = 320;
    private static final int PANEL_H = 264;
    private static final int THUMB_MAX_W = 288;
    private static final int THUMB_MAX_H = 144;

    // Amber tint overlaid on the inverted thumbnail to evoke color-negative orange mask
    private static final int NEGATIVE_TINT = 0x30FF9B10;

    private final List<PhotoData> exposures;
    private final int filmType;
    private int index = 0;

    private ThumbImage thumb = null;
    private boolean thumbMissing = false;
    private int loadedForIndex = -1;

    public FilmStripScreen(ItemStack stack) {
        super(Component.literal("NEGATIVE"));
        FilmRollData film = stack.get(ModDataComponents.FILM_ROLL);
        if (film != null && !film.exposures().isEmpty()) {
            this.exposures = film.exposures();
            this.filmType  = film.filmType();
        } else {
            this.exposures = List.of();
            this.filmType  = FilmKind.COLOR_400;
        }
    }

    protected void init() {
        if (!exposures.isEmpty() && loadedForIndex != index) {
            loadThumb(exposures.get(index));
        }

        int cx = width / 2;
        int py = (height - PANEL_H) / 2;

        if (exposures.isEmpty()) {
            addRenderableWidget(SafelightButton.ghost(cx - 50, py + PANEL_H - 28, 100,
                    Component.literal("← 閉じる"), b -> onClose()));
            return;
        }

        int navY = py + PANEL_H - 56;
        addRenderableWidget(SafelightButton.of(cx - 105, navY, 100,
                Component.literal("◀ PREV"), b -> navigate(-1)));
        addRenderableWidget(SafelightButton.of(cx + 5, navY, 100,
                Component.literal("NEXT ▶"), b -> navigate(1)));

        addRenderableWidget(SafelightButton.ghost(cx - 50, py + PANEL_H - 28, 100,
                Component.literal("← 閉じる"), b -> onClose()));
    }

    private void navigate(int dir) {
        index = Math.max(0, Math.min(exposures.size() - 1, index + dir));
        rebuildWidgets();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {}

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0xFF101010);

        int cx = width / 2;
        int px = cx - PANEL_W / 2;
        int py = (height - PANEL_H) / 2;

        GuiHelper.drawPanel(ctx, px, py, PANEL_W, PANEL_H);
        GuiHelper.drawNameplate(ctx, px + 6, py + 5, PANEL_W - 12);
        GuiHelper.drawRule(ctx, px + 6, py + 17, PANEL_W - 12);
        ctx.centeredText(font, Component.literal("NEGATIVE"), cx, py + 6, GuiHelper.BRASS_BRIGHT);

        if (exposures.isEmpty()) {
            ctx.centeredText(font,
                    Component.literal("NO EXPOSURES"), cx, py + PANEL_H / 2 - 5, GuiHelper.CREAM_FAINT);
            super.extractRenderState(ctx, mouseX, mouseY, delta);
            return;
        }

        int thumbAreaTop = py + 22;
        if (thumbMissing) {
            ctx.fill(cx - THUMB_MAX_W / 2, thumbAreaTop,
                    cx + THUMB_MAX_W / 2, thumbAreaTop + THUMB_MAX_H, 0xFF1A1000);
            ctx.centeredText(font,
                    Component.literal("[ NO FILE ]"),
                    cx, thumbAreaTop + THUMB_MAX_H / 2 - 4, GuiHelper.CREAM_FAINT);
        } else if (thumb != null) {
            int tx = cx - thumb.guiW() / 2;
            int ty = thumbAreaTop + (THUMB_MAX_H - thumb.guiH()) / 2;
            // Amber sprocket-hole border
            ctx.fill(tx - 1, ty - 1, tx + thumb.guiW() + 1, ty + thumb.guiH() + 1, 0xFFB07018);
            ctx.blit(RenderPipelines.GUI_TEXTURED, thumb.id(), tx, ty, 0f, 0f,
                    thumb.guiW(), thumb.guiH(), thumb.texW(), thumb.texH(), thumb.texW(), thumb.texH());
            // Amber negative-mask tint over colour film
            if (!FilmKind.isBW(filmType)) {
                ctx.fill(tx, ty, tx + thumb.guiW(), ty + thumb.guiH(), NEGATIVE_TINT);
            }
        }

        PhotoData p = exposures.get(index);
        int metaY = py + 22 + THUMB_MAX_H + 4;
        int lineH = font.lineHeight + 2;

        String counter = (index + 1) + " / " + exposures.size();
        ctx.centeredText(font, Component.literal(counter), cx, metaY, GuiHelper.CREAM);
        metaY += lineH;

        String exposure = String.format("F%.1f  %s  ISO%d  %dmm",
                p.cameraAtCapture().aperture(),
                shutterLabel(p.cameraAtCapture().shutterSpeedIdx()),
                p.cameraAtCapture().iso(),
                p.cameraAtCapture().focalLengthMm());
        ctx.centeredText(font, Component.literal(exposure), cx, metaY, GuiHelper.BRASS_BRIGHT);
        metaY += lineH;

        String loc = shortDim(p.dimension()) + "  (" + p.x() + ", " + p.y() + ", " + p.z() + ")";
        ctx.centeredText(font, Component.literal(loc), cx, metaY, GuiHelper.CREAM_DIM);

        if (p.fogged()) {
            metaY += lineH;
            ctx.centeredText(font, Component.literal("⚠ 光被り"), cx, metaY, GuiHelper.EMBER);
        }

        super.extractRenderState(ctx, mouseX, mouseY, delta);
    }

    // -------------------------------------------------------------------------
    // Thumbnail loading with colour inversion
    // -------------------------------------------------------------------------

    private void loadThumb(PhotoData data) {
        thumb = null;
        thumbMissing = false;
        loadedForIndex = index;

        Minecraft mc = Minecraft.getInstance();
        File file = new File(mc.gameDirectory, "photographica/photos/" + data.id() + ".png");
        if (!file.isFile()) {
            thumbMissing = true;
            return;
        }

        NativeImage original = null;
        NativeImage inverted = null;
        NativeImage forTexture = null;
        try (FileInputStream fis = new FileInputStream(file)) {
            original = NativeImage.read(fis);

            // Invert colours to simulate negative appearance
            inverted = invertColors(original, FilmKind.isBW(filmType));

            float aspect = (float) inverted.getWidth() / inverted.getHeight();
            int guiW, guiH;
            if (THUMB_MAX_W / aspect <= THUMB_MAX_H) {
                guiW = THUMB_MAX_W;
                guiH = Math.max(1, (int) (THUMB_MAX_W / aspect));
            } else {
                guiH = THUMB_MAX_H;
                guiW = Math.max(1, (int) (THUMB_MAX_H * aspect));
            }

            double sf = mc.getWindow().getGuiScale();
            int physW = Math.max(1, (int) Math.round(guiW * sf));
            int physH = Math.max(1, (int) Math.round(guiH * sf));

            if (physW >= inverted.getWidth()) {
                forTexture = inverted;
                inverted = null;
                physW = forTexture.getWidth();
                physH = forTexture.getHeight();
            } else {
                forTexture = boxResample(inverted, physW, physH);
            }

            String safeId = data.id().toString().replace('-', '_').toLowerCase();
            Identifier texId = Identifier.fromNamespaceAndPath(Photographica.MOD_ID, "negthumb/" + safeId);
            final NativeImage finalTexture = forTexture;
            forTexture = null;
            DynamicTexture tex = new DynamicTexture(() -> "negthumb/" + safeId, finalTexture);
            mc.getTextureManager().register(texId, tex);

            thumb = new ThumbImage(texId, physW, physH, guiW, guiH);
        } catch (IOException e) {
            Photographica.LOGGER.error("Failed to load negative thumbnail {}", data.id(), e);
            thumbMissing = true;
        } finally {
            if (forTexture != null) forTexture.close();
            if (inverted != null) inverted.close();
            if (original != null) original.close();
        }
    }

    private static NativeImage invertColors(NativeImage src, boolean bw) {
        int w = src.getWidth(), h = src.getHeight();
        NativeImage dst = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = getPixelAbgr(src, x, y);
                int a =  (c >>> 24) & 0xFF;
                int b = ~(c >>> 16) & 0xFF;
                int g = ~(c >>>  8) & 0xFF;
                int r = ~c          & 0xFF;
                setPixelAbgr(dst, x, y, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
        return dst;
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

    private static int getPixelAbgr(NativeImage img, int x, int y) {
        int argb = img.getPixel(x, y);
        int a=(argb>>>24)&0xFF; int r=(argb>>>16)&0xFF; int g=(argb>>>8)&0xFF; int b=argb&0xFF;
        return (a<<24)|(b<<16)|(g<<8)|r;
    }
    private static void setPixelAbgr(NativeImage img, int x, int y, int abgr) {
        int a=(abgr>>>24)&0xFF; int b=(abgr>>>16)&0xFF; int g=(abgr>>>8)&0xFF; int r=abgr&0xFF;
        img.setPixel(x, y, (a<<24)|(r<<16)|(g<<8)|b);
    }
}
