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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Displays a captured photo. Loads the PNG from
 * <gameDir>/photographica/photos/<uuid>.png on first open and caches the
 * registered texture by UUID.
 */
@Environment(EnvType.CLIENT)
public class PhotoViewerScreen extends Screen {
	private record CachedImage(Identifier id, int width, int height) {}

	private static final Map<UUID, CachedImage> CACHE = new HashMap<>();

	private final PhotoData data;
	private CachedImage image;
	private boolean missing = false;

	public PhotoViewerScreen(PhotoData data) {
		super(Text.literal("Photo"));
		this.data = data;
	}

	@Override
	protected void init() {
		ensureLoaded();

		addDrawableChild(ButtonWidget.builder(Text.literal("閉じる"), b -> close())
				.dimensions(width / 2 - 40, height - 24, 80, 20)
				.build());
	}

	private void ensureLoaded() {
		UUID id = data.id();
		image = CACHE.get(id);
		if (image != null || missing) return;

		MinecraftClient mc = MinecraftClient.getInstance();
		File file = new File(mc.runDirectory, "photographica/photos/" + id + ".png");
		if (!file.isFile()) {
			Photographica.LOGGER.warn("Photo PNG not found: {}", file);
			missing = true;
			return;
		}

		try (FileInputStream fis = new FileInputStream(file)) {
			NativeImage native_ = NativeImage.read(fis);
			NativeImageBackedTexture tex = new NativeImageBackedTexture(native_);
			String safeId = id.toString().replace('-', '_').toLowerCase();
			Identifier texId = Identifier.of(Photographica.MOD_ID, "photo/" + safeId);
			mc.getTextureManager().registerTexture(texId, tex);
			image = new CachedImage(texId, native_.getWidth(), native_.getHeight());
			CACHE.put(id, image);
		} catch (IOException e) {
			Photographica.LOGGER.error("Failed to load photo {}", id, e);
			missing = true;
		}
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		this.renderBackground(ctx, mouseX, mouseY, delta);

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
		float scaleW = (width * 0.9f) / image.width;
		float scaleH = (height * 0.78f) / image.height;
		float scale = Math.min(scaleW, scaleH);
		int dw = Math.max(1, (int) (image.width * scale));
		int dh = Math.max(1, (int) (image.height * scale));
		int dx = (width - dw) / 2;
		int dy = (height - dh) / 2 - 8;

		// frame
		ctx.fill(dx - 2, dy - 2, dx + dw + 2, dy + dh + 2, 0xFFFFFFFF);
		ctx.fill(dx - 1, dy - 1, dx + dw + 1, dy + dh + 1, 0xFF000000);

		ctx.drawTexture(image.id, dx, dy, dw, dh, 0f, 0f,
				image.width, image.height, image.width, image.height);
	}

	private void renderMetadata(DrawContext ctx) {
		String header = "撮影者: " + data.photographer();
		String exposure = String.format("F%.1f  ISO%d  ×%.1f",
				data.cameraAtCapture().aperture(),
				data.cameraAtCapture().iso(),
				data.cameraAtCapture().zoom());
		String location = String.format("%s (%d, %d, %d)",
				data.dimension(), data.x(), data.y(), data.z());

		ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(header), width / 2, 6, 0xFFFFFFFF);
		ctx.drawTextWithShadow(textRenderer, Text.literal(exposure), 8, height - 40, 0xFFB0B0B0);
		ctx.drawTextWithShadow(textRenderer, Text.literal(location), 8, height - 28, 0xFF808080);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
