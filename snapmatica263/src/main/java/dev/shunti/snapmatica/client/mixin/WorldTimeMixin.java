package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.ApertureIntegration;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Run the world clock at EXPOSURE time for the length of an aperture burst.
 *
 * <p>See the 1.21.x tree's copy for the reasoning. The only difference here is the name: 26
 * replaced {@code getTimeOfDay} with a world-clock system, and what the sky is drawn from is
 * {@code getOverworldClockTime}.
 *
 * <p>Client levels only. In single-player the integrated server shares this class, and the
 * server's clock has no business moving because someone took a photograph.
 */
@Mixin(Level.class)
public class WorldTimeMixin {
    @Inject(method = "getOverworldClockTime", at = @At("HEAD"), cancellable = true)
    private void snapmatica$exposureClock(CallbackInfoReturnable<Long> cir) {
        if (!ApertureIntegration.isActive()) return;
        if (!((Object) this instanceof net.minecraft.client.multiplayer.ClientLevel)) return;
        long held = ApertureIntegration.heldWorldTime();
        if (held >= 0L) cir.setReturnValue(held);
    }
}
