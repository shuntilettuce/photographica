package dev.hitom.photographica.client.screen;

import dev.hitom.photographica.Photographica;
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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Displays a captured photo. The PNG is loaded from
 * <gameDir>/photographica/photos/<uuid>.png and resampled (box filter) so the
 * texture resolution matches the on-screen physical pixel size — that way the
 * GPU samples it at 1:1 and the photo stays crisp regardless of GUI scale.
 * The texture is regenerated whenever init() runs (open / window resize).
 */
@Environment(EnvType.CLIENT)
public class PhotoViewerScreen extends Screen {
    private record LoadedImage(Identifier id, int texW, int texH, int guiW, int guiH) {}

    private final PhotoData data;
    private final Screen parent;
    private LoadedImage image;
    private boolean missing = false;

    public PhotoViewerScreen(PhotoData data) {
        this(data, null);
    }

    public PhotoViewerScreen(PhotoData data, Screen parent) {
        super(Component.literal("Photo"));
        this.data = data;
        this.parent = parent;
    }

    protected void init() {
        // Regenerate the texture on every init (covers initial open and window resize).
        image = null;
        missing = false;
        loadImage();

        addRenderableWidget(SafelightButton.ghost(width / 2 - 40, height - 24, 80,
                Component.literal(parent != null ? "← 戻る" : "閉じる"),
                b -> onClose()));
    }

    private void loadImage() {
        UUID id = data.id();
        Minecraft mc = Minecraft.getInstance();
        File file = new File(mc.gameDirectory, "photographica/photos/" + id + ".png");
        if (!file.isFile()) {
            Photographica.LOGGER.warn("Photo PNG not found: {}", file);
            missing = true;
            return;
        }

        NativeImage original = null;
        NativeImage forTexture = null;
        try (FileInputStream fis = new FileInputStream(file)) {
            original = NativeImage.read(fis);

            // GUI display size (constrained to 90% width, 78% height)
            float aspect = (float) original.getWidth() / original.getHeight();
            int maxGuiW = Math.max(16, (int) (this.width * 0.9f));
            int maxGuiH = Math.max(16, (int) (this.height * 0.78f));
            int guiW, guiH;
            if (maxGuiW / aspect <= maxGuiH) {
                guiW = maxGuiW;
                guiH = Math.max(1, (int) (maxGuiW / aspect));
            } else {
                guiH = maxGuiH;
                guiW = Math.max(1, (int) (maxGuiH * aspect));
            }

            // Physical pixel size after GUI scale matrix is applied
            double sf = mc.getWindow().getGuiScale();
            int physW = Math.max(1, (int) Math.round(guiW * sf));
            int physH = Math.max(1, (int) Math.round(guiH * sf));

            if (physW >= original.getWidth()) {
                // Upscale case — use source as-is, let LINEAR filter handle bilinear interp
                forTexture = original;
                original = null;
                physW = forTexture.getWidth();
                physH = forTexture.getHeight();
            } else {
                forTexture = boxResample(original, physW, physH);
            }

            String safeId = id.toString().replace('-', '_').toLowerCase();
            Identifier texId = Identifier.fromNamespaceAndPath(Photographica.MOD_ID, "photo/" + safeId);
            final NativeImage finalTexture = forTexture;
            forTexture = null;
            DynamicTexture tex = new DynamicTexture(() -> "photo/" + safeId, finalTexture);
            mc.getTextureManager().register(texId, tex);

            image = new LoadedImage(texId, physW, physH, guiW, guiH);
        } catch (IOException e) {
            Photographica.LOGGER.error("Failed to load photo {}", id, e);
            missing = true;
        } finally {
            if (forTexture != null) forTexture.close();
            if (original != null) original.close();
        }
    }

    private static NativeImage boxResample(NativeImage src, int dw, int dh) {
        int sw = src.getWidth();
        int sh = src.getHeight();
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
                for (int sy = sy0; sy < sy1; sy++) {
                    for (int sx = sx0; sx < sx1; sx++) {
                        int c = getPixelAbgr(src, sx, sy);
                        aa += (c >>> 24) & 0xFF;
                        ba += (c >>> 16) & 0xFF;
                        ga += (c >>> 8) & 0xFF;
                        ra += c & 0xFF;
                        n++;
                    }
                }
                int color = (((int) (aa / n)) << 24)
                        | (((int) (ba / n)) << 16)
                        | (((int) (ga / n)) << 8)
                        | ((int) (ra / n));
                setPixelAbgr(dst, x, y, color);
            }
        }
        return dst;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float deltaTicks) {
        // Override to no-op — the inherited renderBackground calls applyBlur which
        // would blur both the world AND our already-drawn photo via super.extractRenderState().
        // We draw our own simple darken in render() instead.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0xC0101010);

        if (missing) {
            ctx.centeredText(font,
                    Component.literal("[ 写真ファイルが見つかりません ]"),
                    width / 2, height / 2 - 6, 0xFFFF5555);
            ctx.centeredText(font,
                    Component.literal(data.id().toString()),
                    width / 2, height / 2 + 8, 0xFF808080);
        } else if (image != null) {
            renderImage(ctx);
        }

        renderMetadata(ctx);
        super.extractRenderState(ctx, mouseX, mouseY, delta);
    }

    private void renderImage(GuiGraphicsExtractor ctx) {
        int dw = image.guiW;
        int dh = image.guiH;
        int dx = (width - dw) / 2;
        int dy = (height - dh) / 2 - 8;

        // frame
        ctx.fill(dx - 2, dy - 2, dx + dw + 2, dy + dh + 2, 0xFFFFFFFF);
        ctx.fill(dx - 1, dy - 1, dx + dw + 1, dy + dh + 1, 0xFF000000);

        ctx.blit(RenderPipelines.GUI_TEXTURED, image.id, dx, dy, 0f, 0f,
                dw, dh, image.texW, image.texH);

        // Fogging overlay — washes out photos exposed to light during handling/development.
        if (data.fogged()) {
            ctx.fill(dx, dy, dx + dw, dy + dh, 0xC8FFFFFF);
            ctx.centeredText(font,
                    Component.literal("§c光被り"),
                    dx + dw / 2, dy + dh / 2 - 4, 0xFFFF4444);
        }
    }

    private void renderMetadata(GuiGraphicsExtractor ctx) {
        String header = "撮影者: " + data.photographer();
        String exposure = String.format("F%.1f  ISO%d  %dmm",
                data.cameraAtCapture().aperture(),
                data.cameraAtCapture().iso(),
                data.cameraAtCapture().focalLengthMm());
        String location = String.format("%s (%d, %d, %d)",
                data.dimension(), data.x(), data.y(), data.z());

        ctx.centeredText(font, Component.literal(header), width / 2, 6, 0xFFFFFFFF);
        ctx.text(font, Component.literal(exposure), 8, height - 40, 0xFFB0B0B0, true);
        ctx.text(font, Component.literal(location), 8, height - 28, 0xFF808080, true);
    }

    @Override
    public void onClose() {
        if (parent != null) {
            Minecraft.getInstance().setScreen(parent);
        } else {
            super.onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
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
