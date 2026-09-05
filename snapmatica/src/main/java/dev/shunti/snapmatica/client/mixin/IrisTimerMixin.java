package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.ApertureIntegration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Run the shader pack's animation clock at EXPOSURE time for the length of a burst.
 *
 * <p>A burst is one photograph taken as a hundred-odd rendered frames, and everything that moves
 * across them smears. The first version of this mixin stopped the clock outright, which fixed a
 * photograph marked 1/480 s looking like a six-second exposure and broke the other end of the
 * dial in the same stroke: with the clock stopped, 30 seconds and 1/4000 produce the same frozen
 * water and the same rigid grass. The shutter has to mean something.
 *
 * <p>So the clock is not stopped, it is re-based. Iris advances {@code frameTimeCounter} by the
 * gap between successive {@code beginFrame} calls, so handing it
 * {@link ApertureIntegration#animationClockMillis} — which returns the shutter's own clock while
 * a burst is running and the real one otherwise — makes every animation it drives run at the
 * speed of the exposure rather than the speed of the render. Waving grass, water surface,
 * aurora, drifting fog: at 1/1000 s they advance a millisecond across the whole burst and stand
 * still, which is what 1/1000 s means; at 30 s they advance thirty seconds and smear, which is
 * what 30 s means.
 *
 * <p>Modifying the argument rather than the field: {@code beginFrame(long)} is the whole of the
 * contract this needs, and it has been stable across Iris. {@code frameTime} falls out of the
 * same subtraction, so a pack's framerate-independent smoothing slows down with everything else
 * instead of being left running at wall-clock speed inside a virtual exposure.
 *
 * <p>Paired with {@code WorldTimeMixin} and {@code CloudTimeMixin}, which do the same for the sun
 * and the two cloud clocks. {@code @Pseudo} plus a soft target: Iris is optional, and this simply
 * never applies without it.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.SystemTimeUniforms$Timer", remap = false)
public class IrisTimerMixin {
    @ModifyVariable(method = "beginFrame", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private long snapmatica$driveAnimationClock(long timeMillis) {
        return ApertureIntegration.animationClockMillis(timeMillis);
    }
}
