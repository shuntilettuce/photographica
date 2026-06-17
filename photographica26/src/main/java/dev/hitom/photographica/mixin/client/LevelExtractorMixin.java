package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.PhotoCapture;
import dev.hitom.photographica.client.VideoRecorder;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Tripod-recording render fixes. In 26.2 the per-entity visibility/extraction loop
 * moved out of LevelRenderer.extractVisibleEntities into
 * {@link net.minecraft.client.renderer.extract.LevelExtractor} (same method name and
 * signature), and the frustum/shouldRender cull now goes through {@code isEntityVisible}.
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {

    private static final String EXTRACT_VISIBLE_ENTITIES =
            "extractVisibleEntities(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/state/level/LevelRenderState;)V";

    /**
     * During tripod recording the world-render camera sits on the armor stand, but the
     * camera <em>entity</em> is restored to the player. Vanilla then takes the
     * first-person branch and skips the player model. Forcing Camera.isDetached() to
     * report true makes it take the third-person branch so the full body renders.
     */
    @Redirect(
            method = EXTRACT_VISIBLE_ENTITIES,
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
     * Vanilla skips a LocalPlayer entity when camera.entity() != entity (the floating-body
     * guard). Ordinal 3 is the fourth Camera.entity() call — the one in that guard;
     * returning mc.player makes the comparison evaluate equal so the player renders.
     */
    @Redirect(
            method = EXTRACT_VISIBLE_ENTITIES,
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

    /** During tripod recording, hide the camera armor stand so the view from inside its
     *  model is unobstructed. 26.2 routes the frustum cull through isEntityVisible(). */
    @Redirect(
            method = "isEntityVisible(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z",
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
