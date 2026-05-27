package dev.hitom.photographica.client.screen;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public class SafelightButton extends ButtonWidget {

    public enum Style { DEFAULT, PRIMARY, GHOST }

    private final Style style;

    private SafelightButton(int x, int y, int w, Text msg, PressAction action, Style style) {
        super(x, y, w, 20, msg, action, DEFAULT_NARRATION_SUPPLIER);
        this.style = style;
    }

    public static SafelightButton of(int x, int y, int w, Text msg, PressAction action) {
        return new SafelightButton(x, y, w, msg, action, Style.DEFAULT);
    }

    public static SafelightButton primary(int x, int y, int w, Text msg, PressAction action) {
        return new SafelightButton(x, y, w, msg, action, Style.PRIMARY);
    }

    public static SafelightButton ghost(int x, int y, int w, Text msg, PressAction action) {
        return new SafelightButton(x, y, w, msg, action, Style.GHOST);
    }

    @Override
    protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
        int x = getX(), y = getY(), w = getWidth();

        // outer border
        ctx.fill(x, y, x + w, y + 20, GuiHelper.FRAME_LO);

        // gradient body
        switch (style) {
            case PRIMARY -> {
                ctx.fill(x + 1, y + 1, x + w - 1, y + 7,  0xFFD35A3A);
                ctx.fill(x + 1, y + 7, x + w - 1, y + 14, GuiHelper.SAFELIGHT);
                ctx.fill(x + 1, y + 14, x + w - 1, y + 19, GuiHelper.SAFELIGHT_DIM);
            }
            case GHOST -> {
                ctx.fill(x + 1, y + 1, x + w - 1, y + 7,  GuiHelper.PANEL_2);
                ctx.fill(x + 1, y + 7, x + w - 1, y + 14, GuiHelper.PANEL);
                ctx.fill(x + 1, y + 14, x + w - 1, y + 19, GuiHelper.PANEL_SHADOW);
            }
            default -> {
                ctx.fill(x + 1, y + 1, x + w - 1, y + 7,  GuiHelper.PANEL_LIGHT);
                ctx.fill(x + 1, y + 7, x + w - 1, y + 14, GuiHelper.PANEL);
                ctx.fill(x + 1, y + 14, x + w - 1, y + 19, GuiHelper.PANEL_SHADOW);
            }
        }

        // inner top bevel
        ctx.fill(x + 1, y + 1, x + w - 1, y + 2, GuiHelper.FRAME_HI);

        // label color
        int textColor = switch (style) {
            case PRIMARY -> 0xFFFFF5E8;
            case GHOST   -> GuiHelper.CREAM_DIM;
            default      -> GuiHelper.CREAM;
        };

        var tr = MinecraftClient.getInstance().textRenderer;
        ctx.drawCenteredTextWithShadow(tr, getMessage(), x + w / 2, y + 6, textColor);
    }
}
