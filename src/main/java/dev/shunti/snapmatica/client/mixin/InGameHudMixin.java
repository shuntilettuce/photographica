package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.SnapmaticaClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.Timer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the hotbar and the vanilla crosshair while the viewfinder is up (sneaking or
 * freecam) — the hotbar sits inside the photo frame and has nothing to do with composing a
 * shot, and the crosshair is redundant with (and visually clutters) this mod's own AF
 * reticle, which already draws over the same spot.
 */
@Mixin(Gui.class)
public abstract class InGameHudMixin {

    // 1.20.1's renderHotbar takes (float tickDelta, GuiGraphics context) — reversed order and
    // no Timer (introduced in 1.21).
    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void snapmatica$hideHotbar(float tickDelta, GuiGraphics context, CallbackInfo ci) {
        if (SnapmaticaClient.viewfinderActive(Minecraft.getInstance())) {
            ci.cancel();
        }
    }

    // renderCrosshair mirrors renderHotbar's own signature split — (GuiGraphics,
    // Timer) from 1.21, plain (GuiGraphics) with no tick delta at all before it.
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void snapmatica$hideCrosshair(GuiGraphics context, CallbackInfo ci) {
        if (SnapmaticaClient.viewfinderActive(Minecraft.getInstance())) {
            ci.cancel();
        }
    }
}
