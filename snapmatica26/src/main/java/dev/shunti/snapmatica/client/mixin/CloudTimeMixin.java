package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.ApertureIntegration;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Run vanilla's clouds on the exposure clock for the length of a burst.
 *
 * <p>The clouds scroll on an accumulated tick count handed to the renderer as its own argument,
 * which is a different clock from the world's. 26 calls the method {@code render} and passes
 * the tick as its only {@code long}.
 */
@Mixin(CloudRenderer.class)
public class CloudTimeMixin {
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private long snapmatica$cloudExposureClock(long ticks) {
        long held = ApertureIntegration.heldCloudTicks(ticks);
        return held >= 0L ? held : ticks;
    }
}
