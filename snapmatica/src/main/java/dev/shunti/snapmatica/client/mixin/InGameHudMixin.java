package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.SnapmaticaClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
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
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

    // 1.20.1's renderHotbar takes (float tickDelta, DrawContext context) — reversed order and
    // no RenderTickCounter (introduced in 1.21).
    //? if >=1.21 {
    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void snapmatica$hideHotbar(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (SnapmaticaClient.viewfinderActive(MinecraftClient.getInstance())) {
            ci.cancel();
        }
    }
    //?} else {
    /*@Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void snapmatica$hideHotbar(float tickDelta, DrawContext context, CallbackInfo ci) {
        if (SnapmaticaClient.viewfinderActive(MinecraftClient.getInstance())) {
            ci.cancel();
        }
    }
    *///?}

    // renderCrosshair mirrors renderHotbar's own signature split — (DrawContext,
    // RenderTickCounter) from 1.21, plain (DrawContext) with no tick delta at all before it.
    //? if >=1.21 {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void snapmatica$hideCrosshair(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (SnapmaticaClient.viewfinderActive(MinecraftClient.getInstance())) {
            ci.cancel();
        }
    }
    //?} else {
    /*@Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void snapmatica$hideCrosshair(DrawContext context, CallbackInfo ci) {
        if (SnapmaticaClient.viewfinderActive(MinecraftClient.getInstance())) {
            ci.cancel();
        }
    }
    *///?}
}
