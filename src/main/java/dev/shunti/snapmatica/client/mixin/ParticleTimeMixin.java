package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.ApertureIntegration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hold the particles still while the burst is being read out.
 *
 * <p>A burst renders its samples over consecutive real frames, and the minecraft tick loop keeps
 * running throughout -- it has to, this is a minecraft mod and stopping it would desync a server.
 * Particles ride that tick, so they kept moving at WALL-CLOCK speed while the samples were
 * taken, and every sample caught them somewhere else. A 1/250 s photograph, which should freeze
 * rain in the air, instead stacked sixty-four copies of each drop across the second or so the
 * burst took to read out: not a smear but a dotted line, because a particle is small and bright
 * and its copies do not overlap.
 *
 * <p>The exposure has already happened by then. The interval is recorded first, one minecraft tick
 * at a time, for exactly the shutter time -- and the samples that follow are all of THAT
 * interval, replayed. So no further time is supposed to pass while they are read, and holding
 * the particles is not freezing the world, it is declining to let one system keep walking after
 * the shutter has shut.
 *
 * <p>Safe to hold in a way almost nothing else here would be: particles are minecraft-side and
 * decorative, known to no server and to no other system. Nothing observes that they paused, and
 * they resume from where they stood the instant the readout ends.
 *
 * <p>What this does NOT do is smear them across a long exposure. Entities get that from
 * {@code EntityExposure}, which records a render state per instant and replays it; particles
 * have no equivalent handle -- they are created and destroyed constantly with no stable
 * identity to record against -- so a thirty-second frame shows them at one instant rather than
 * drawn out. That is still wrong, but it is a coherent instant instead of the wrong amount of
 * motion chopped into sixty-four pieces.
 */
@Mixin(net.minecraft.client.particle.ParticleEngine.class)
public class ParticleTimeMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void snapmatica$holdForExposure(CallbackInfo ci) {
        if (ApertureIntegration.holdParticles()) ci.cancel();
    }

    /**
     * Proof, at compile time, that the method this mixin injects into exists.
     *
     * <p>{@code method = "tick"} is a string resolved when the game starts, and a mixin that
     * fails to find its target does not degrade -- with {@code required: true} it takes the
     * launch down. Across seven Minecraft versions that is a real risk and has happened here
     * before (1.21.3's input tick). Naming the method in Java makes every target's compile the
     * check: if it is ever renamed or given arguments, the build breaks on the version where
     * that happened rather than the player's launcher.
     */
    private static void snapmatica$targetExists(net.minecraft.client.particle.ParticleEngine p) {
        p.tick();
    }
}
