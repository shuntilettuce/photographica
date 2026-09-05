package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.SnapmaticaClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the hotbar while the viewfinder is up (sneaking or freecam) — it sits inside the
 * photo frame and has nothing to do with composing a shot.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {

    @Inject(method = "extractItemHotbar", at = @At("HEAD"), cancellable = true)
    private void snapmatica$hideHotbar(GuiGraphicsExtractor ctx, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (SnapmaticaClient.viewfinderActive(Minecraft.getInstance())) {
            ci.cancel();
        }
    }
}
