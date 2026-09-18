package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.CameraScrollHandler;
//? if >=26 {
/*import net.minecraft.client.MouseHandler;*/
//?} else {
import net.minecraft.client.Mouse;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts mouse scroll events for camera parameter adjustment
 * while the viewfinder is active (player sneaking).
 */
//? if >=26 {
/*@Mixin(MouseHandler.class)*/
//?} else {
@Mixin(Mouse.class)
//?}
public class MouseMixin {

    //? if >=26 {
    /*@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)*/
    //?} else {
    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    //?}
    private void snapmatica$onMouseScroll(long window, double horizontal, double vertical,
                                          CallbackInfo ci) {
        if (CameraScrollHandler.onScroll(vertical)) {
            ci.cancel();
        }
    }
}
