package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.ApertureIntegration;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Run the world clock at EXPOSURE time for the length of an aperture burst.
 *
 * <p>A burst is one photograph taken as a few hundred rendered frames, and left alone they are a
 * few hundred DIFFERENT instants — the render's, not the shutter's. Photon's clouds make that
 * plain: it declares its own uniform
 *
 * <pre>  uniform.float.world_age = ((worldDay % 128) * 24000.0 + worldTime) / 20.0  </pre>
 *
 * and drifts every cloud layer along it — {@code vec3 wind = vec3(wind_velocity * world_age,
 * 0.0).xzy}. So the clouds, and the shadows they throw across the whole scene, marched across
 * the exposure at the speed of the RENDER: six seconds of weather in a photograph marked
 * 1/480 s. Averaging those frames averaged the shadows into a flat wash, which is why the
 * picture came back veiled, why contrast collapsed everywhere at once rather than in patches,
 * and why individual samples arrived far lighter or darker than their neighbours depending only
 * on what was overhead at the time.
 *
 * <p>The first fix was to stop the clock, which cured that and made the shutter dial
 * meaningless in the same stroke — 30 s and 1/4000 both returning a sky nailed in place. What
 * this returns now is the instant of the exposure the current sub-frame stands for:
 * {@code t0 + τ_i}, with {@code τ_i} spread across the shutter exactly as the pupil offsets are
 * spread across the aperture. At 1/1000 s that is the same tick for every sample and the sky
 * does stand still, because that is what 1/1000 s means. At 30 s it is 600 ticks — nine degrees
 * of sun, shadows sweeping, clouds streaking.
 *
 * <p>{@code worldDay} and {@code worldTime} both come from here, so this one method carries the
 * clouds, the sun and everything else a pack drives from the world clock. Iris's own
 * {@code frameTimeCounter} is a separate clock, driven by {@link IrisTimerMixin}, and vanilla's
 * clouds a third, driven by {@code CloudTimeMixin}.
 *
 * <p>Client worlds only. In single-player the integrated server shares this class, and the
 * server's clock has no business moving because someone took a photograph.
 */
@Mixin(World.class)
public class WorldTimeMixin {
    @Inject(method = "getTimeOfDay", at = @At("HEAD"), cancellable = true)
    private void snapmatica$exposureClock(CallbackInfoReturnable<Long> cir) {
        if (!ApertureIntegration.isActive()) return;
        if (!((Object) this instanceof net.minecraft.client.world.ClientWorld)) return;
        long held = ApertureIntegration.heldWorldTime();
        if (held >= 0L) cir.setReturnValue(held);
    }
}
