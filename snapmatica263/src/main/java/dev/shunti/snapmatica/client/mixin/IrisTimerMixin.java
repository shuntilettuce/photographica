package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.ApertureIntegration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Run the shader pack's animation clock at EXPOSURE time for the length of a burst.
 *
 * <p>{@code @Pseudo} plus a soft target: Iris is optional, and on 26 there may be no Iris at
 * all yet, in which case this simply never applies. Iris advances {@code frameTimeCounter} by
 * the gap between successive calls, so handing it a virtual clock is the whole of it.
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.SystemTimeUniforms$Timer", remap = false)
public class IrisTimerMixin {
    // require = 0 finishes what @Pseudo starts. @Pseudo tolerates Iris being ABSENT; it does
    // nothing for Iris being present in a version whose timer lost beginFrame, and with the
    // mixin config's defaultRequire that is a crash on launch. An optional integration should
    // degrade to "the exposure clock does not reach Iris" rather than take the game down.
    @ModifyVariable(method = "beginFrame", at = @At("HEAD"), argsOnly = true, ordinal = 0,
            require = 0)
    private long snapmatica$driveAnimationClock(long timeMillis) {
        return ApertureIntegration.animationClockMillis(timeMillis);
    }
}
