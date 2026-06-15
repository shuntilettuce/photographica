package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.PhotoCapture;
import dev.shunti.snapmatica.client.SnapmaticaClient;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    /**
     * Overrides the perspective FOV that extractCamera() passes to Projection.setupPerspective().
     * In MC 26.1.2 the old getFov() method no longer exists; the FOV is passed directly into
     * Projection.setupPerspective(fov, zNear, zFar, width, height) inside extractCamera().
     * We intercept argument index 0 (fov) and replace it with the focal-length-derived FOV
     * when the player is looking through the viewfinder.
     */
    @ModifyArg(
            method = "extractCamera(Lnet/minecraft/client/DeltaTracker;FF)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/Projection;setupPerspective(FFFFF)V"),
            index = 0
    )
    private float snapmatica$applyFocalLength(float fov) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return fov;
        if (!SnapmaticaClient.viewfinderSneakEnabled || !player.isShiftKeyDown()) return fov;
        if (SnapmaticaClient.lensType == 0) return fov;
        int f = SnapmaticaClient.focalLengthMm;
        if (f <= 0) return fov;
        double halfSensorMm = SnapmaticaClient.portraitOrientation ? 18.0 : 12.0;
        return (float) Math.toDegrees(2.0 * Math.atan(halfSensorMm / f));
    }

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
                    shift = At.Shift.AFTER))
    private void snapmatica$captureAfterLevel(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        PhotoCapture.captureIfPending();
    }
}
