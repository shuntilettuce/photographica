package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.ApertureIntegration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Run vanilla's clouds on the exposure clock for the length of a burst.
 *
 * <p>Re-basing the world clock ({@code WorldTimeMixin}) carries the sun and everything a shader
 * pack drives from {@code worldTime}. It does not carry vanilla's clouds: they scroll on the
 * accumulated tick count, handed to {@code renderClouds} as its own argument, which is a
 * different clock entirely. A burst is one exposure, and a sky drifting through it at RENDER
 * speed averages its own shadows into a flat wash — measured under a shader pack, sub-frame
 * brightness spread 3.4x before the clocks were taken in hand and 0.5% after. Vanilla's clouds
 * are the same mechanism with a different clock behind it.
 *
 * <p>So the argument becomes {@code c0 + τ_i}: the tick the clouds stood at when the shutter
 * opened, plus how far into the exposure this sub-frame is. A fast shutter rounds every sample
 * to the same tick and the sky is nailed in place; thirty seconds moves them six hundred, which
 * at vanilla's scroll rate is eighteen blocks of streak.
 *
 * <p>Rewriting the argument is narrower than touching the counter it comes from: nothing else in
 * the game changes behaviour, and the clouds resume from wherever the world left them the moment
 * the shutter closes.
 */
//? if >=1.21.10 {
@Mixin(net.minecraft.client.render.CloudRenderer.class)
public class CloudTimeMixin {
    @ModifyVariable(method = "renderClouds", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private long snapmatica$cloudExposureClock(long ticks) {
        long held = ApertureIntegration.heldCloudTicks(ticks);
        return held >= 0L ? held : ticks;
    }
}
//?} else {
/*@Mixin(net.minecraft.client.render.WorldRenderer.class)
public class CloudTimeMixin {
    // Vanilla's cloud animation is not reachable as a single argument below 1.21.10, so a burst
    // there still sees the sky drift. Documented rather than approximated.
}
*///?}
