package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;

/**
 * Shared look for snapmatica's screens — the darkroom palette and button style photographica
 * uses, so the two mods read as the same equipment rather than two different programs.
 *
 * <p>A dim safelight red against warm near-black panels, with brass edging and cream text.
 */
@Environment(EnvType.CLIENT)
public final class CameraUi {
    private CameraUi() {}

    public static final int PANEL_SHADOW  = 0xFF15110D;
    public static final int PANEL         = 0xFF221D18;
    public static final int PANEL_2       = 0xFF2C2620;
    public static final int PANEL_LIGHT   = 0xFF3A3128;
    public static final int SAFELIGHT_DIM = 0xFF7A1F17;
    public static final int SAFELIGHT     = 0xFFC2362B;
    public static final int SAFELIGHT_HI  = 0xFFD35A3A;
    public static final int FRAME_HI      = 0xFF4A3F30;
    public static final int FRAME_LO      = 0xFF14100C;
    public static final int CREAM         = 0xFFE8DCC4;
    public static final int CREAM_DIM     = 0xFF9A8D72;

    /**
     * photographica's SafelightButton: a bevelled three-band gradient in three weights.
     *
     * <p>{@code Text} is spelled out in full throughout — {@code ButtonWidget} has a nested type
     * of that name which would otherwise shadow the import.
     */
    public static class Button extends ButtonWidget {
        public enum Style { DEFAULT, PRIMARY, GHOST }

        private final Style style;

        private Button(int x, int y, int w, net.minecraft.text.Text msg,
                       PressAction action, Style style) {
            super(x, y, w, 20, msg, action, DEFAULT_NARRATION_SUPPLIER);
            this.style = style;
        }

        public static Button of(int x, int y, int w, net.minecraft.text.Text msg, PressAction a) {
            return new Button(x, y, w, msg, a, Style.DEFAULT);
        }

        public static Button primary(int x, int y, int w, net.minecraft.text.Text msg, PressAction a) {
            return new Button(x, y, w, msg, a, Style.PRIMARY);
        }

        public static Button ghost(int x, int y, int w, net.minecraft.text.Text msg, PressAction a) {
            return new Button(x, y, w, msg, a, Style.GHOST);
        }

        private void drawBody(DrawContext ctx) {
            int x = getX(), y = getY(), w = getWidth();
            ctx.fill(x, y, x + w, y + 20, FRAME_LO);
            switch (style) {
                case PRIMARY -> {
                    ctx.fill(x + 1, y + 1,  x + w - 1, y + 7,  SAFELIGHT_HI);
                    ctx.fill(x + 1, y + 7,  x + w - 1, y + 14, SAFELIGHT);
                    ctx.fill(x + 1, y + 14, x + w - 1, y + 19, SAFELIGHT_DIM);
                }
                case GHOST -> {
                    ctx.fill(x + 1, y + 1,  x + w - 1, y + 7,  PANEL_2);
                    ctx.fill(x + 1, y + 7,  x + w - 1, y + 14, PANEL);
                    ctx.fill(x + 1, y + 14, x + w - 1, y + 19, PANEL_SHADOW);
                }
                default -> {
                    ctx.fill(x + 1, y + 1,  x + w - 1, y + 7,  PANEL_LIGHT);
                    ctx.fill(x + 1, y + 7,  x + w - 1, y + 14, PANEL);
                    ctx.fill(x + 1, y + 14, x + w - 1, y + 19, PANEL_SHADOW);
                }
            }
            ctx.fill(x + 1, y + 1, x + w - 1, y + 2, FRAME_HI);
        }

        private int labelColour() {
            return switch (style) {
                case PRIMARY -> 0xFFFFF5E8;
                case GHOST   -> CREAM_DIM;
                default      -> CREAM;
            };
        }

        // 1.21.11 made renderWidget final and split painting into drawIcon plus a text
        // consumer, so the label composites in the correct layer above the fills. Earlier
        // versions draw the whole button inline.
        //? if >=1.21.11 {
        /*@Override
        protected void drawIcon(DrawContext ctx, int mouseX, int mouseY, float delta) {
            drawBody(ctx);
            drawLabel(ctx.getHoverListener(this, DrawContext.HoverType.NONE));
        }

        @Override
        protected void drawLabel(net.minecraft.client.font.DrawnTextConsumer consumer) {
            net.minecraft.text.Text label = getMessage().copy().styled(s -> s.withColor(
                    net.minecraft.text.TextColor.fromRgb(labelColour() & 0xFFFFFF)));
            drawTextWithMargin(consumer, label, 2);
        }
        *///?} else {
        @Override
        protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
            drawBody(ctx);
            var tr = MinecraftClient.getInstance().textRenderer;
            ctx.drawCenteredTextWithShadow(tr, getMessage(),
                    getX() + getWidth() / 2, getY() + 6, labelColour());
        }
        //?}
    }
}
