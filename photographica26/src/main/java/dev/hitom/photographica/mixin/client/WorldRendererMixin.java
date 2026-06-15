package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.PhotoCapture;
import dev.hitom.photographica.client.VideoRecorder;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class WorldRendererMixin {

    /**
     * Suppresses the block-selection outline during any capture or recording,
     * so it never bleeds into photos or video frames.
     */
    @Inject(
            method = "renderHitOutline",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void photographica$hideOutlineDuringCapture(CallbackInfo ci) {
        if (PhotoCapture.isCapturePending() || VideoRecorder.isRecording()) {
            ci.cancel();
        }
    }

    /**
     * During tripod recording the world-render camera is positioned at the armor stand,
     * but the camera *entity* is restored to the player (for input/pick safety). Vanilla
     * extractVisibleEntities() then treats the player as the camera's own entity in
     * first-person mode and skips its model entirely — leaving only the shadow, which is
     * exactly the "transparent player" bug.
     *
     * Forcing Camera.isDetached() to report true makes vanilla take the third-person
     * branch, so the player's full body renders (the camera is already at the stand, so
     * third-person framing is what we want). Scoped to extractVisibleEntities only.
     */
    @Redirect(
            method = "extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/Camera;isDetached()Z"),
            require = 0
    )
    private boolean photographica$forceDetachedDuringTripod(Camera camera) {
        if (PhotoCapture.armorStandCapturePending || VideoRecorder.isTripodRecording()) {
            return true;
        }
        return camera.isDetached();
    }

    /**
     * Vanilla LevelRenderer.extractVisibleEntities() skips a LocalPlayer entity when
     * camera.entity() != entity (the "floating body" guard). This also fires when the mod
     * redirects the camera to an armor-stand for a photo, making the player invisible.
     *
     * Ordinal 3 is the fourth Camera.entity() call in extractVisibleEntities — the one in
     * the LocalPlayer guard: returning mc.player makes the comparison evaluate to equal,
     * so the player renders.
     */
    @Redirect(
            method = "extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V",
            at = @At(value = "INVOKE", ordinal = 3,
                    target = "Lnet/minecraft/client/Camera;entity()Lnet/minecraft/world/entity/Entity;"),
            require = 0
    )
    private Entity photographica$allowPlayerRenderDuringArmorStandCapture(Camera camera) {
        if (PhotoCapture.armorStandCapturePending
                || VideoRecorder.isTripodRecording()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) return mc.player;
        }
        return camera.entity();
    }

    /** During tripod recording, prevent the camera armor stand from being rendered
     *  so the view from inside its model is unobstructed. */
    @Redirect(
            method = "extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z"),
            require = 0
    )
    private boolean photographica$hideTripodStandFromRender(EntityRenderDispatcher dispatcher, Entity entity,
            Frustum frustum, double x, double y, double z) {
        if (VideoRecorder.isTripodRecording()
                && entity.getId() == VideoRecorder.getRecordingArmorStandEntityId()) {
            return false;
        }
        return dispatcher.shouldRender(entity, frustum, x, y, z);
    }
}
