package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.PhotoCapture;
import dev.shunti.snapmatica.client.SnapmaticaClient;
import dev.shunti.snapmatica.client.VideoRecorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import com.mojang.blaze3d.vertex.PoseStack;

@Mixin(ItemInHandRenderer.class)
public class HeldItemRendererMixin {

    @Inject(method = "renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/player/LocalPlayer;I)V",
            at = @At("HEAD"), cancellable = true)
    private void snapmatica$suppressHand(float tickDelta, PoseStack matrices,
                                          SubmitNodeCollector queue,
                                          LocalPlayer player, int light, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        // Recording is covered here as well as at GameRenderer.renderItemInHand. Cancelling the
        // outer call alone was not enough with Iris installed — the held item still reached
        // the footage — so it is stopped at the item renderer too, which every path goes
        // through. Belt and braces on purpose: a hand in one frame ruins the take.
        if (PhotoCapture.isCapturePending() || VideoRecorder.isRecording()
                || SnapmaticaClient.viewfinderActive(mc)) {
            ci.cancel();
        }
    }
}
