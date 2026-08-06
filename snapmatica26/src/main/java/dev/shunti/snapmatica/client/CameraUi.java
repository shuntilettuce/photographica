package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

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
     * <p>{@code Button} is abstract here and paints through {@code extractContents}; skipping
     * {@code extractDefaultSprite} is what replaces the vanilla look with this one.
     */
    public static class SnapButton extends Button {
        public enum Style { DEFAULT, PRIMARY, GHOST }

        private final Style style;

        private SnapButton(int x, int y, int w, Component msg, OnPress action, Style style) {
            super(x, y, w, 20, msg, action, DEFAULT_NARRATION);
            this.style = style;
        }

        public static SnapButton of(int x, int y, int w, Component msg, OnPress a) {
            return new SnapButton(x, y, w, msg, a, Style.DEFAULT);
        }

        public static SnapButton primary(int x, int y, int w, Component msg, OnPress a) {
            return new SnapButton(x, y, w, msg, a, Style.PRIMARY);
        }

        public static SnapButton ghost(int x, int y, int w, Component msg, OnPress a) {
            return new SnapButton(x, y, w, msg, a, Style.GHOST);
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
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

            int colour = switch (style) {
                case PRIMARY -> 0xFFFFF5E8;
                case GHOST   -> CREAM_DIM;
                default      -> CREAM;
            };
            ctx.centeredText(Minecraft.getInstance().font, getMessage(),
                    x + w / 2, y + 6, colour);
        }
    }
}
