package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.PhotoCapture;
import dev.hitom.photographica.client.VideoRecorder;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
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
     * Vanilla LevelRenderer.renderLevel() skips drawing a {@code LocalPlayer} when
     * {@code camera.entity() != entity}. This is intentional for spectating
     * (you shouldn't see your own floating body), but it also fires when the mod
     * redirects the camera to an armor-stand for a photo — making the player
     * invisible in the shot.
     *
     * We redirect the getFocusedEntity() call (ordinal 3) to return {@code mc.player}
     * during an armor-stand capture so the comparison evaluates to {@code false}
     * and the player entity is rendered normally.
     */
    @Redirect(
            method = "fillEntityRenderStates(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/renderer/LevelRenderer$RenderChunkStorage;)V",
            at = @At(value = "INVOKE", ordinal = 3,
                    target = "Lnet/minecraft/client/Camera;getEntity()Lnet/minecraft/world/entity/Entity;"),
            require = 0
    )
    private Entity photographica$allowPlayerRenderDuringArmorStandCapture(Camera camera) {
        if (PhotoCapture.armorStandCapturePending) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) return mc.player;
        }
        return camera.entity();
    }
}
