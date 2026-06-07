package dev.hitom.photographica.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public class SafelightButton extends Button {

    public enum Style { DEFAULT, PRIMARY, GHOST }

    private final Style style;

    private SafelightButton(int x, int y, int w, Component msg, OnPress action, Style style) {
        super(x, y, w, 20, msg, action, DEFAULT_NARRATION);
        this.style = style;
    }

    public static SafelightButton of(int x, int y, int w, Component msg, OnPress action) {
        return new SafelightButton(x, y, w, msg, action, Style.DEFAULT);
    }

    public static SafelightButton primary(int x, int y, int w, Component msg, OnPress action) {
        return new SafelightButton(x, y, w, msg, action, Style.PRIMARY);
    }

    public static SafelightButton ghost(int x, int y, int w, Component msg, OnPress action) {
        return new SafelightButton(x, y, w, msg, action, Style.GHOST);
    }

    protected void extractContents(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        int x = getX(), y = getY(), w = getWidth();
        ctx.fill(x, y, x + w, y + 20, GuiHelper.FRAME_LO);
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
        ctx.fill(x + 1, y + 1, x + w - 1, y + 2, GuiHelper.FRAME_HI);
        int textColor = switch (style) {
            case PRIMARY -> 0xFFFFF5E8;
            case GHOST   -> GuiHelper.CREAM_DIM;
            default      -> GuiHelper.CREAM;
        };
        var tr = Minecraft.getInstance().font;
        ctx.centeredText(tr, getMessage(), x + w / 2, y + 6, textColor);
    }
}
