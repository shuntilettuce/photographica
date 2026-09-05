package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
//? if >=1.21 {
import net.minecraft.client.render.RenderTickCounter;
//?}
import net.minecraft.text.Text;

/**
 * A bottom-right corner readout for freecam: the running distance from the last recorded
 * camera-path keyframe, so keyframes can be spaced evenly for constant playback speed — see
 * Freecam.pathDurationSec. Used to also list every freecam keybind, but once those collapsed
 * into the single Camera Path menu (P) there was nothing left worth a permanent hint — X and C
 * are learned once and remembered like any other control.
 */
@Environment(EnvType.CLIENT)
public final class FreecamHud {
    private FreecamHud() {}

    private static final int PAD = 6;
    private static final int MARGIN = 6;

    // See ViewfinderOverlay.render for why this splits at 1.21 (RenderTickCounter vs float).
    //? if >=1.21 {
    public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
    //?} else {
    /*public static void render(DrawContext ctx, float tickDelta) {
    *///?}
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        if (!Freecam.isActive() || mc.currentScreen != null) return;

        TextRenderer tr = mc.textRenderer;
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();

        int n = Freecam.pathKeyframeCount();
        String status = (n == 0)
                ? Text.translatable("snapmatica.freecam.hud_no_keyframes").getString()
                : Text.translatable("snapmatica.freecam.hud_status", n,
                        String.format("%.1f", Freecam.distanceFromLastKeyframe())).getString();

        int textW = tr.getWidth(status);
        int boxW = textW + PAD * 2;
        int boxH = tr.fontHeight + PAD * 2;
        int x2 = sw - MARGIN, y2 = sh - MARGIN;
        int x1 = x2 - boxW, y1 = y2 - boxH;

        ctx.fill(x1, y1, x2, y2, 0x90000000);
        ctx.drawTextWithShadow(tr, status, x1 + PAD, y1 + PAD, CameraUi.CREAM);
    }
}
