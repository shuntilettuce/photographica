package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.PhotoCapture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
//? if >=1.21.4 {
/*import net.minecraft.client.render.entity.state.EntityRenderState;*/
//?} else {
import net.minecraft.entity.Entity;
//?}
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides entity name tags during photo capture so they don't appear in photos.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    //? if >=1.21.4 {
    /*@Inject(
            method = "renderLabelIfPresent",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void snapmatica$hideLabelDuringCapture(EntityRenderState state, Text text, MatrixStack matrices,
                                                   VertexConsumerProvider vertexConsumers, int light,
                                                   CallbackInfo ci) {
        if (PhotoCapture.isCapturePending()) {
            ci.cancel();
        }
    }*/
    //?} else {
    @Inject(
            method = "renderLabelIfPresent",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private <T extends Entity> void snapmatica$hideLabelDuringCapture(T entity, Text text, MatrixStack matrices,
                                                   VertexConsumerProvider vertexConsumers, int light,
                                                   float tickDelta, CallbackInfo ci) {
        if (PhotoCapture.isCapturePending()) {
            ci.cancel();
        }
    }
    //?}
}
