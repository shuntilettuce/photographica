package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.DronePilot;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the hotbar and health/hunger/armor status bars while piloting a drone — the viewfinder
 * frame (see {@code DroneSignalHud}) already covers that whole area of the screen, and the
 * pilot isn't holding an item or taking damage through the drone anyway, so the ordinary
 * survival HUD elements just read as visual clutter over the "camera footage" look. Everything
 * else (crosshair, effects, chat, boss bar) is left alone.
 */
@Mixin(InGameHud.class)
public class InGameHudMixin {

    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void photographica$hideHotbarWhilePiloting(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (DronePilot.isActive()) ci.cancel();
    }

    @Inject(method = "renderStatusBars", at = @At("HEAD"), cancellable = true)
    private void photographica$hideStatusBarsWhilePiloting(DrawContext context, CallbackInfo ci) {
        if (DronePilot.isActive()) ci.cancel();
    }
}
