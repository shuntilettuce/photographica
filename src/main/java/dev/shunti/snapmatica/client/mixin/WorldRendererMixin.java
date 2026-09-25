package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.PhotoCapture;
import dev.shunti.snapmatica.client.SnapmaticaClient;
import dev.shunti.snapmatica.client.VideoRecorder;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the targeted-block outline during photo capture, video recording, and whenever the
 * viewfinder is up (sneaking or freecam) — a black wireframe box sitting in the middle of the
 * frame has nothing to do with composing a shot, on top of never belonging in the saved photo
 * or recorded footage in the first place.
 */
@Mixin(LevelRenderer.class)
public class WorldRendererMixin {

    @Inject(
            method = "renderHitOutline(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;DDDLnet/minecraft/client/renderer/state/BlockOutlineRenderState;IF)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void snapmatica$hideOutlineDuringCapture(CallbackInfo ci) {
        if (PhotoCapture.isCapturePending() || VideoRecorder.isRecording()
                || SnapmaticaClient.viewfinderActive(Minecraft.getInstance())) {
            ci.cancel();
        }
    }
}
