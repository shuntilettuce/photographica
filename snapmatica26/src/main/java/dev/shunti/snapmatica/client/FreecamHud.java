package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * A bottom-right corner readout for freecam: the running distance from the last recorded
 * camera-path keyframe, so keyframes can be spaced evenly for constant playback speed — see
 * Freecam.pathDurationSec. Used to also list every freecam keybind, but once those collapsed
 * into the single Camera Path menu (Q) there was nothing left worth a permanent hint.
 */
@Environment(EnvType.CLIENT)
public final class FreecamHud {
    private FreecamHud() {}

    private static final int PAD = 6;
    private static final int MARGIN = 6;

    public static void render(GuiGraphicsExtractor ctx, DeltaTracker tickCounter) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        if (!Freecam.isActive() || mc.screen != null) return;

        Font font = mc.font;
        int sw = ctx.guiWidth(), sh = ctx.guiHeight();

        int n = Freecam.pathKeyframeCount();
        String status = (n == 0)
                ? Component.translatable("snapmatica.freecam.hud_no_keyframes").getString()
                : Component.translatable("snapmatica.freecam.hud_status", n,
                        String.format("%.1f", Freecam.distanceFromLastKeyframe())).getString();

        int textW = font.width(status);
        int boxW = textW + PAD * 2;
        int boxH = font.lineHeight + PAD * 2;
        int x2 = sw - MARGIN, y2 = sh - MARGIN;
        int x1 = x2 - boxW, y1 = y2 - boxH;

        ctx.fill(x1, y1, x2, y2, 0x90000000);
        ctx.text(font, status, x1 + PAD, y1 + PAD, CameraUi.CREAM);
    }
}
