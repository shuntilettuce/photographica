package dev.hitom.photographica.client.screen;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.component.PhotoData;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

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
	private final net.minecraft.client.gui.screen.Screen parent;
	private LoadedImage image;
	private boolean missing = false;

	public PhotoViewerScreen(PhotoData data) {
		this(data, null);
	}

	public PhotoViewerScreen(PhotoData data, net.minecraft.client.gui.screen.Screen parent) {
		super(Text.literal("Photo"));
		this.data = data;
		this.parent = parent;
	}

	@Override
	protected void init() {
		// Regenerate the texture on every init (covers initial open and window resize).
		image = null;
		missing = false;
		loadImage();

		addDrawableChild(SafelightButton.ghost(width / 2 - 40, height - 24, 80,
				Text.literal(parent != null ? "← 戻る" : "閉じる"),
				b -> close()));
	}

	private void loadImage() {
		UUID id = data.id();
		MinecraftClient mc = MinecraftClient.getInstance();
		File file = new File(mc.runDirectory, "photographica/photos/" + id + ".png");
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
			double sf = mc.getWindow().getScaleFactor();
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
			Identifier texId = Identifier.of(Photographica.MOD_ID, "photo/" + safeId);
			//? if >=1.21.11 {
			/*NativeImageBackedTexture tex = new NativeImageBackedTexture(() -> "photo/" + safeId, forTexture);*/
			//?} else {
			NativeImageBackedTexture tex = new NativeImageBackedTexture(forTexture);
			//?}
			//? if <1.21.11 {
			tex.setFilter(true, false);
			//?}
			mc.getTextureManager().registerTexture(texId, tex);
			// Ownership of forTexture transferred to the texture; null it out so
			// the cleanup block below doesn't double-close it.
			forTexture = null;

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
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
		// Override to no-op — the inherited renderBackground calls applyBlur which
		// would blur both the world AND our already-drawn photo via super.render().
		// We draw our own simple darken in render() instead.
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		ctx.fill(0, 0, this.width, this.height, 0xC0101010);

		if (missing) {
			ctx.drawCenteredTextWithShadow(textRenderer,
					Text.literal("[ 写真ファイルが見つかりません ]"),
					width / 2, height / 2 - 6, 0xFFFF5555);
			ctx.drawCenteredTextWithShadow(textRenderer,
					Text.literal(data.id().toString()),
					width / 2, height / 2 + 8, 0xFF808080);
		} else if (image != null) {
			renderImage(ctx);
		}

		renderMetadata(ctx);
		super.render(ctx, mouseX, mouseY, delta);
	}

	private void renderImage(DrawContext ctx) {
		int dw = image.guiW;
		int dh = image.guiH;
		int dx = (width - dw) / 2;
		int dy = (height - dh) / 2 - 8;

		// frame
		ctx.fill(dx - 2, dy - 2, dx + dw + 2, dy + dh + 2, 0xFFFFFFFF);
		ctx.fill(dx - 1, dy - 1, dx + dw + 1, dy + dh + 1, 0xFF000000);

		//? if >=1.21.11 {
		/*ctx.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, image.id, dx, dy, 0f, 0f,
				image.texW, image.texH, image.texW, image.texH, dw, dh);*/
		//?} else if >=1.21.4 {
		/*ctx.drawTexture(net.minecraft.client.render.RenderLayer::getGuiTextured, image.id, dx, dy, 0f, 0f,
				image.texW, image.texH, image.texW, image.texH, dw, dh);*/
		//?} else {
		ctx.drawTexture(image.id, dx, dy, dw, dh, 0f, 0f,
				image.texW, image.texH, image.texW, image.texH);
		//?}

		// Fogging overlay — washes out photos exposed to light during handling/development.
		if (data.fogged()) {
			ctx.fill(dx, dy, dx + dw, dy + dh, 0xC8FFFFFF);
			ctx.drawCenteredTextWithShadow(textRenderer,
					net.minecraft.text.Text.literal("§c光被り"),
					dx + dw / 2, dy + dh / 2 - 4, 0xFFFF4444);
		}
	}

	private void renderMetadata(DrawContext ctx) {
		String header = "撮影者: " + data.photographer();
		String exposure = String.format("F%.1f  ISO%d  %dmm",
				data.cameraAtCapture().aperture(),
				data.cameraAtCapture().iso(),
				data.cameraAtCapture().focalLengthMm());
		String location = String.format("%s (%d, %d, %d)",
				data.dimension(), data.x(), data.y(), data.z());

		ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(header), width / 2, 6, 0xFFFFFFFF);
		ctx.drawTextWithShadow(textRenderer, Text.literal(exposure), 8, height - 40, 0xFFB0B0B0);
		ctx.drawTextWithShadow(textRenderer, Text.literal(location), 8, height - 28, 0xFF808080);
	}

	@Override
	public void close() {
		if (parent != null) {
			MinecraftClient.getInstance().setScreen(parent);
		} else {
			super.close();
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
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
