package dev.hitom.photographica.client.screen;

import dev.hitom.photographica.client.render.PhotoTextureCache;
import dev.hitom.photographica.component.PhotoData;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Displays a captured photo, via {@link PhotoTextureCache} — which transparently fetches the
 * PNG from the server if this client doesn't have it locally (e.g. someone else's photo), so
 * this screen just polls {@code getOrLoad} every frame until it resolves rather than loading
 * once in {@code init()}.
 */
@Environment(EnvType.CLIENT)
public class PhotoViewerScreen extends Screen {
	private final PhotoData data;
	private final net.minecraft.client.gui.screen.Screen parent;

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
		addDrawableChild(SafelightButton.ghost(width / 2 - 40, height - 24, 80,
				Text.literal(parent != null ? "← 戻る" : "閉じる"),
				b -> close()));
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

		Identifier tex = PhotoTextureCache.getOrLoad(data.id());
		if (tex != null) {
			renderImage(ctx, tex);
		} else if (PhotoTextureCache.isFetching(data.id())) {
			ctx.drawCenteredTextWithShadow(textRenderer,
					Text.literal("[ 読み込み中… ]"),
					width / 2, height / 2 - 6, 0xFFCCCCCC);
		} else {
			ctx.drawCenteredTextWithShadow(textRenderer,
					Text.literal("[ 写真ファイルが見つかりません ]"),
					width / 2, height / 2 - 6, 0xFFFF5555);
			ctx.drawCenteredTextWithShadow(textRenderer,
					Text.literal(data.id().toString()),
					width / 2, height / 2 + 8, 0xFF808080);
		}

		renderMetadata(ctx);
		super.render(ctx, mouseX, mouseY, delta);
	}

	private void renderImage(DrawContext ctx, Identifier tex) {
		int[] sz = PhotoTextureCache.getSize(data.id());
		int texW = sz != null ? sz[0] : 1;
		int texH = sz != null ? sz[1] : 1;

		// GUI display size (constrained to 90% width, 78% height)
		float aspect = (float) texW / texH;
		int maxGuiW = Math.max(16, (int) (this.width * 0.9f));
		int maxGuiH = Math.max(16, (int) (this.height * 0.78f));
		int dw, dh;
		if (maxGuiW / aspect <= maxGuiH) {
			dw = maxGuiW;
			dh = Math.max(1, (int) (maxGuiW / aspect));
		} else {
			dh = maxGuiH;
			dw = Math.max(1, (int) (maxGuiH * aspect));
		}
		int dx = (width - dw) / 2;
		int dy = (height - dh) / 2 - 8;

		// frame
		ctx.fill(dx - 2, dy - 2, dx + dw + 2, dy + dh + 2, 0xFFFFFFFF);
		ctx.fill(dx - 1, dy - 1, dx + dw + 1, dy + dh + 1, 0xFF000000);

		//? if >=1.21.11 {
		/*ctx.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, tex, dx, dy, 0f, 0f, dw, dh, texW, texH, texW, texH);*/
		//?} else {
		//? if >=1.21.4 {
		/*ctx.drawTexture(net.minecraft.client.render.RenderLayer::getGuiTextured, tex, dx, dy, 0f, 0f, dw, dh, texW, texH, texW, texH);*/
		//?} else {
		ctx.drawTexture(tex, dx, dy, dw, dh, 0f, 0f, texW, texH, texW, texH);
		//?}
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
		String dim = data.dimension();
		int dimColon = dim.lastIndexOf(':');
		if (dimColon >= 0) dim = dim.substring(dimColon + 1);
		String location = String.format("%s (%d, %d, %d)",
				dim, data.x(), data.y(), data.z());

		ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(header), width / 2, 6, 0xFFFFFFFF);
		// Camera settings sit bottom-left and dimension+coords bottom-right so the
		// two no longer stack and overlap the buttons below.
		ctx.drawTextWithShadow(textRenderer, Text.literal(exposure), 8, height - 20, 0xFFB0B0B0);
		int locW = textRenderer.getWidth(location);
		ctx.drawTextWithShadow(textRenderer, Text.literal(location), width - 8 - locW, height - 20, 0xFF808080);
	}

	@Override
	public void close() {
		if (parent != null) {
			net.minecraft.client.MinecraftClient.getInstance().setScreen(parent);
		} else {
			super.close();
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
