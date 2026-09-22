package dev.shunti.snapmatica.client;


import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.DeltaTracker;
import net.minecraft.network.chat.Component;

/**
 * A bottom-right corner readout for freecam: the running distance from the last recorded
 * camera-path keyframe, so keyframes can be spaced evenly for constant playback speed — see
 * Freecam.pathDurationSec. Used to also list every freecam keybind, but once those collapsed
 * into the single Camera Path menu (P) there was nothing left worth a permanent hint — X and C
 * are learned once and remembered like any other control.
 */

public final class FreecamHud {
    private FreecamHud() {}

    private static final int PAD = 6;
    private static final int MARGIN = 6;

    // See ViewfinderOverlay.render for why this splits at 1.21 (DeltaTracker vs float).
    public static void render(GuiGraphics ctx, DeltaTracker tickCounter) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        if (!Freecam.isActive() || mc.screen != null) return;

        Font tr = mc.font;
        int sw = ctx.guiWidth(), sh = ctx.guiHeight();

        int n = Freecam.pathKeyframeCount();
        String status = (n == 0)
                ? Component.translatable("snapmatica.freecam.hud_no_keyframes").getString()
                : Component.translatable("snapmatica.freecam.hud_status", n,
                        String.format("%.1f", Freecam.distanceFromLastKeyframe())).getString();

        int textW = tr.width(status);
        int boxW = textW + PAD * 2;
        int boxH = tr.lineHeight + PAD * 2;
        int x2 = sw - MARGIN, y2 = sh - MARGIN;
        int x1 = x2 - boxW, y1 = y2 - boxH;

        ctx.fill(x1, y1, x2, y2, 0x90000000);
        ctx.drawString(tr, status, x1 + PAD, y1 + PAD, CameraUi.CREAM);
    }
}
