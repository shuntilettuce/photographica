package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.CameraScrollHandler;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MouseMixin {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void snapmatica$onScroll(long window, double horizontal, double vertical,
                                     CallbackInfo ci) {
        if (CameraScrollHandler.onScroll(vertical)) {
            ci.cancel();
        }
    }
}
