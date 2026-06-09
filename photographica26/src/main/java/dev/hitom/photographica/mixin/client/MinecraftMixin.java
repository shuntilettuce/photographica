package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.VideoRecorder;
import dev.hitom.photographica.client.screen.VideoCameraScreen;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When recording from a tripod (armor stand), intercept the pause-game action
 * (triggered by Escape) and open the stop-recording screen instead of the
 * vanilla game menu.  Without this the player is stuck: the view is locked to
 * the armor stand and pressing Escape just opens the unrelated pause menu.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Inject(method = "pauseGame", at = @At("HEAD"), cancellable = true)
    private void photographica$interceptPauseDuringArmorStandRecording(boolean paused, CallbackInfo ci) {
        int standId = VideoRecorder.getRecordingArmorStandEntityId();
        if (standId < 0) return;
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new VideoCameraScreen(VideoRecorder.getRecordingStack(), standId));
        ci.cancel();
    }
}
