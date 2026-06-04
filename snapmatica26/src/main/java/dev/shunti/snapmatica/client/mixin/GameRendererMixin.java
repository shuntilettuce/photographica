package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.PhotoCapture;
import dev.shunti.snapmatica.client.SnapmaticaClient;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "getFov(Lnet/minecraft/client/Camera;FZ)F",
            at = @At("RETURN"),
            cancellable = true)
    private void snapmatica$applyFocalLength(Camera camera, float tickDelta, boolean changingFov,
                                             CallbackInfoReturnable<Float> cir) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;
        if (!SnapmaticaClient.viewfinderSneakEnabled || !player.isShiftKeyDown()) return;
        if (SnapmaticaClient.lensType == 0) return;
        int f = SnapmaticaClient.focalLengthMm;
        if (f <= 0) return;
        cir.setReturnValue((float) Math.toDegrees(2.0 * Math.atan(12.0 / f)));
    }

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
                    shift = At.Shift.AFTER))
    private void snapmatica$captureAfterComposite(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        PhotoCapture.captureIfPending();
    }
}
