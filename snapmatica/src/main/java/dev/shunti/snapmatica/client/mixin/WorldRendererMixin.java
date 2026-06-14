package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.PhotoCapture;
import dev.shunti.snapmatica.client.VideoRecorder;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the targeted-block outline during photo capture and video recording, so the
 * black selection wireframe never appears in the saved photo or the recorded footage.
 */
@Mixin(WorldRenderer.class)
public class WorldRendererMixin {

    //? if >=1.21.11 {
    /*@Inject(
            method = "drawBlockOutline(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;DDDLnet/minecraft/client/render/state/OutlineRenderState;IF)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void snapmatica$hideOutlineDuringCapture(CallbackInfo ci) {
        if (PhotoCapture.isCapturePending() || VideoRecorder.isRecording()) {
            ci.cancel();
        }
    }*/
    //?} else if >=1.21.4 {
    /*@Inject(
            method = "drawBlockOutline(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;Lnet/minecraft/entity/Entity;DDDLnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void snapmatica$hideOutlineDuringCapture(CallbackInfo ci) {
        if (PhotoCapture.isCapturePending() || VideoRecorder.isRecording()) {
            ci.cancel();
        }
    }*/
    //?} else {
    @Inject(
            method = "drawBlockOutline(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;Lnet/minecraft/entity/Entity;DDDLnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void snapmatica$hideOutlineDuringCapture(MatrixStack matrices, VertexConsumer vertexConsumer,
                                                     Entity entity, double cameraX, double cameraY, double cameraZ,
                                                     BlockPos pos, BlockState state, CallbackInfo ci) {
        if (PhotoCapture.isCapturePending() || VideoRecorder.isRecording()) {
            ci.cancel();
        }
    }
    //?}
}
