package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.PhotoCapture;
import dev.shunti.snapmatica.client.SnapmaticaClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.item.HeldItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=1.21.11 {
/*import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.util.math.MatrixStack;
*///?}

@Mixin(HeldItemRenderer.class)
public class HeldItemRendererMixin {

    //? if >=1.21.11 {
    /*@Inject(method = "renderItem(FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/network/ClientPlayerEntity;I)V",
            at = @At("HEAD"), cancellable = true)
    private void snapmatica$suppressHand(float tickDelta, MatrixStack matrices,
                                          OrderedRenderCommandQueue queue,
                                          ClientPlayerEntity player, int light, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (PhotoCapture.isCapturePending() ||
                (SnapmaticaClient.viewfinderSneakEnabled && mc.player != null && mc.player.isSneaking())) {
            ci.cancel();
        }
    }
    *///?}
}
