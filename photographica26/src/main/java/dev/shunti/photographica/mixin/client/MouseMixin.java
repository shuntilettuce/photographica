package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.CameraScrollHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts mouse scroll events so that, while the camera viewfinder is active
 * (player sneaking with a camera in hand), the scroll wheel adjusts focal length
 * or aperture instead of switching the hotbar slot.
 */
@Mixin(MouseHandler.class)
public class MouseMixin {
    // Tracks whether lockMouse() was called more recently than releaseMouse(),
    // so the isMouseGrabbed override only applies when the game actually wants
    // the cursor captured (in-game) rather than free (main menu / GUI screens).
    private static boolean photographica$wantsLock = false;

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void photographica$onMouseScroll(long window, double horizontal, double vertical,
                                             CallbackInfo ci) {
        if (CameraScrollHandler.onScroll(vertical)) {
            ci.cancel();
        }
    }

    @Inject(method = "grabMouse", at = @At("HEAD"))
    private void photographica$onLockCursor(CallbackInfo ci) {
        photographica$wantsLock = true;
    }

    @Inject(method = "releaseMouse", at = @At("HEAD"))
    private void photographica$onUnlockCursor(CallbackInfo ci) {
        photographica$wantsLock = false;
    }

    // In Xvfb dev environments GLFW cursor capture never succeeds, so the game
    // stays in "click to play" state indefinitely. Force-report locked only when
    // the game actually called grabMouse(), so the main menu remains interactive.
    @Inject(method = "isMouseGrabbed", at = @At("RETURN"), cancellable = true)
    private void photographica$forceLockedInDev(CallbackInfoReturnable<Boolean> cir) {
        if (FabricLoader.getInstance().isDevelopmentEnvironment()
                && photographica$wantsLock && !cir.getReturnValue()) {
            cir.setReturnValue(true);
        }
    }
}
