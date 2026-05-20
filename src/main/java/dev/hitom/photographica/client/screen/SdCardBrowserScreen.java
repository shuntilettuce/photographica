package dev.hitom.photographica.client.screen;

import dev.hitom.photographica.component.PhotoData;
import dev.hitom.photographica.component.SdCardData;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Playback screen for browsing photos stored on an SD card inside a camera.
 * Prev/Next to cycle through photos; "全画面" opens PhotoViewerScreen with
 * this screen as the parent so the back button returns here.
 */
@Environment(EnvType.CLIENT)
public class SdCardBrowserScreen extends Screen {

    private static final String[] SHUTTERS = {
            "30\"", "15\"", "8\"", "4\"", "2\"", "1\"",
            "1/2", "1/4", "1/8", "1/15", "1/30", "1/60",
            "1/125", "1/250", "1/500", "1/1000", "1/2000", "1/4000"
    };

    private final ItemStack cameraStack;
    private final Screen parent;
    private final List<PhotoData> photos;
    private int index = 0;

    public SdCardBrowserScreen(ItemStack cameraStack, SdCardData sdData, Screen parent) {
        super(Text.literal("SD CARD"));
        this.cameraStack = cameraStack;
        this.parent = parent;
        this.photos = sdData.photos();
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int panelH = 220;
        int py = (height - panelH) / 2;
        int top = py + 30; // below nameplate area

        if (photos.isEmpty()) {
            addDrawableChild(SafelightButton.ghost(cx - 50, py + panelH - 30, 100,
                    Text.literal("← 戻る"), b -> close()));
            return;
        }

        // Prev / Next navigation
        addDrawableChild(SafelightButton.of(cx - 60, top, 50,
                Text.literal("◀ PREV"), b -> { index = Math.max(0, index - 1); clearAndInit(); }));
        addDrawableChild(SafelightButton.of(cx + 10, top, 50,
                Text.literal("NEXT ▶"), b -> { index = Math.min(photos.size() - 1, index + 1); clearAndInit(); }));

        // Full-screen view button
        addDrawableChild(SafelightButton.primary(cx - 105, py + panelH - 56, 100,
                Text.literal("全画面で見る"),
                b -> client.setScreen(new PhotoViewerScreen(photos.get(index), this))));

        // Back button
        addDrawableChild(SafelightButton.ghost(cx + 5, py + panelH - 56, 100,
                Text.literal("← 戻る"), b -> close()));
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {}

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0xFF101010);

        int cx = width / 2;
        int panelW = 320;
        int panelH = 220;
        int px = cx - panelW / 2;
        int py = (height - panelH) / 2;

        GuiHelper.drawPanel(ctx, px, py, panelW, panelH);
        GuiHelper.drawNameplate(ctx, px + 6, py + 5, panelW - 12);
        GuiHelper.drawRule(ctx, px + 6, py + 17, panelW - 12);

        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("SD CARD"), cx, py + 6, GuiHelper.CREAM);

        if (photos.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("NO PHOTOS"), cx, py + panelH / 2 - 5, GuiHelper.CREAM_FAINT);
            super.render(ctx, mouseX, mouseY, delta);
            return;
        }

        int top = py + 30;
        PhotoData p = photos.get(index);

        // Index counter centered between prev/next buttons
        String counter = (index + 1) + " / " + photos.size();
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(counter), cx, top + 6, GuiHelper.CREAM);

        // Metadata block
        int infoY = top + 30;
        int lineH = textRenderer.fontHeight + 3;

        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("撮影者: " + p.photographer()),
                cx, infoY, GuiHelper.CREAM);
        infoY += lineH;

        String exposure = String.format("F%.1f  %s  ISO%d  %dmm",
                p.cameraAtCapture().aperture(),
                shutterLabel(p.cameraAtCapture().shutterSpeedIdx()),
                p.cameraAtCapture().iso(),
                p.cameraAtCapture().focalLengthMm());
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(exposure),
                cx, infoY, GuiHelper.BRASS_BRIGHT);
        infoY += lineH;

        String loc = String.format("%s  (%d, %d, %d)",
                shortDim(p.dimension()), p.x(), p.y(), p.z());
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(loc),
                cx, infoY, GuiHelper.CREAM_DIM);
        infoY += lineH;

        if (p.fogged()) {
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("⚠ 光被り"),
                    cx, infoY, GuiHelper.EMBER);
        }

        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    private static String shutterLabel(int idx) {
        if (idx < 0) return SHUTTERS[0];
        if (idx >= SHUTTERS.length) return SHUTTERS[SHUTTERS.length - 1];
        return SHUTTERS[idx];
    }

    private static String shortDim(String dim) {
        if (dim == null) return "?";
        // "minecraft:overworld" → "overworld"
        int colon = dim.lastIndexOf(':');
        return colon >= 0 ? dim.substring(colon + 1) : dim;
    }
}
