package dev.hitom.photographica.client.hud;

import dev.hitom.photographica.client.VideoRecorder;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

/**
 * HUD overlay for the Camcorder item.
 *
 * Shows one of three states:
 *   ● REC  00:30 / 02:00     — recording (red dot blinks)
 *   ⚙ 後処理中 45%  ████░░░░   — post-processing
 *   ✓ 保存完了: ...            — done (fades out after 5s)
 */
@Environment(EnvType.CLIENT)
public final class VideoRecorderHud {
    private VideoRecorderHud() {}

    private static final int CREAM       = 0xFFE8DCC4;
    private static final int RED_BRIGHT  = 0xFFFF4040;
    private static final int AMBER       = 0xFFE08A3C;
    private static final int GREEN_SOFT  = 0xFF80C880;
    private static final int BG_DARK     = 0xCC0A0807;
    private static final int BAR_FILL    = 0xFFC2362B;
    private static final int BAR_EMPTY   = 0xFF3A3128;

    // How long the "done" banner stays fully visible before fading
    private static final long DONE_HOLD_MS  = 2000L;
    private static final long DONE_FADE_MS  = 3000L;

    public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden || mc.currentScreen != null) return;

        if (VideoRecorder.isRecording()) {
            renderRecording(ctx, mc);
        } else if (VideoRecorder.isPostProcessing()) {
            renderPostProcessing(ctx, mc);
        } else if (VideoRecorder.getDoneAtMs() > 0) {
            renderDone(ctx, mc);
        }
    }

    // ── Recording banner ───────────────────────────────────────────────────────

    private static void renderRecording(DrawContext ctx, MinecraftClient mc) {
        TextRenderer tr = mc.textRenderer;
        int sw = ctx.getScaledWindowWidth();

        long now        = System.currentTimeMillis();
        long elapsed    = now - VideoRecorder.getRecordStartMs();
        long totalMs    = (long) VideoRecorder.MAX_FRAMES * 1000L / VideoRecorder.FPS;
        long remaining  = totalMs - elapsed;

        String elapsedStr  = formatDuration(elapsed);
        String maxStr      = formatDuration(totalMs);
        boolean dotVisible = (now % 1000) < 700;   // blink: 700ms on, 300ms off

        // Warning colour when < 30 s remaining
        int timeColor = remaining < 30_000L ? AMBER : CREAM;

        String recLabel  = dotVisible ? "● REC" : "  REC";
        String timeLabel = elapsedStr + " / " + maxStr;

        int padding = 6;
        int y = 8;

        int w1 = tr.getWidth(recLabel);
        int w2 = tr.getWidth(timeLabel);
        int bannerW = padding * 2 + w1 + 8 + w2;
        int x = sw - bannerW - 8;  // top-right corner

        ctx.fill(x - 2, y - 2, x + bannerW, y + 10, BG_DARK);
        ctx.drawText(tr, recLabel,  x + padding,           y, RED_BRIGHT, false);
        ctx.drawText(tr, timeLabel, x + padding + w1 + 8,  y, timeColor,  false);
    }

    // ── Post-processing banner ─────────────────────────────────────────────────

    private static void renderPostProcessing(DrawContext ctx, MinecraftClient mc) {
        TextRenderer tr = mc.textRenderer;
        int sw  = ctx.getScaledWindowWidth();
        int sh  = ctx.getScaledWindowHeight();

        int progress = VideoRecorder.getPpProgress();
        String msg   = VideoRecorder.getPpMessage();

        int barW    = 160;
        int barH    = 4;
        int panelW  = barW + 20;
        int panelH  = 26;
        int panelX  = (sw - panelW) / 2;
        int panelY  = 10;

        ctx.fill(panelX, panelY, panelX + panelW, panelY + panelH, BG_DARK);

        // Message text
        ctx.drawCenteredTextWithShadow(tr, "⚙ " + msg,
                sw / 2, panelY + 4, CREAM);

        // Progress bar
        int barX = panelX + 10;
        int barY = panelY + 17;
        ctx.fill(barX, barY, barX + barW, barY + barH, BAR_EMPTY);
        int filled = barW * progress / 100;
        if (filled > 0) ctx.fill(barX, barY, barX + filled, barY + barH, BAR_FILL);
    }

    // ── Done banner ────────────────────────────────────────────────────────────

    private static void renderDone(DrawContext ctx, MinecraftClient mc) {
        long now  = System.currentTimeMillis();
        long age  = now - VideoRecorder.getDoneAtMs();
        long total = DONE_HOLD_MS + DONE_FADE_MS;

        if (age > total) {
            // Clear the flag so we stop rendering
            VideoRecorder.doneAtMs = 0L;
            return;
        }

        int alpha;
        if (age < DONE_HOLD_MS) {
            alpha = 0xFF;
        } else {
            long fadeAge = age - DONE_HOLD_MS;
            alpha = (int)(0xFF * (1.0 - (double) fadeAge / DONE_FADE_MS));
        }
        alpha = Math.max(0, Math.min(0xFF, alpha));

        TextRenderer tr  = mc.textRenderer;
        int sw = ctx.getScaledWindowWidth();
        String msg = VideoRecorder.getPpMessage(); // reuse final message ("✓ ..." or "ffmpeg なし ...")

        int col = ((alpha << 24) & 0xFF000000) | (GREEN_SOFT & 0x00FFFFFF);
        ctx.drawCenteredTextWithShadow(tr, msg, sw / 2, 12, col);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static String formatDuration(long ms) {
        if (ms < 0) ms = 0;
        long s   = ms / 1000;
        long min = s / 60;
        long sec = s % 60;
        return String.format("%02d:%02d", min, sec);
    }
}
