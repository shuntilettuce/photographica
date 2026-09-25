package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.ApertureIntegration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drop jump and sneak while the shutter is open, and nothing else.
 *
 * <p>The exposure is a real interval the photographer moves through — that is what makes a pan
 * possible — so walking and looking have to keep working. Two inputs do not belong in a
 * photograph: jump bobs the viewpoint through an arc nobody asked for, and sneak drops it half a
 * block and takes the framing with it. Both are worse here than in ordinary play, because the
 * exposure REPLAYS them: a hop during a half-second shutter is not a hop in the picture, it is
 * every intermediate height at once.
 *
 * <p>Everything else is left alone. This is narrower than the alternative the mod already uses
 * for freecam ({@code player.input = new ClientInput()}, which neutralises the lot) precisely because
 * the movement is wanted.
 *
 * <p>Rebuilding the record rather than clearing fields: {@code Input} is immutable, which
 * is the good kind of obstacle — there is no partially-updated state to get wrong.
 */
@Mixin(net.minecraft.client.player.ClientInput.class)
public class ExposureInputMixin {
    @Inject(method = "tick", at = @At("RETURN"))
    private void snapmatica$dropJumpAndSneakDuringExposure(CallbackInfo ci) {
        if (!ApertureIntegration.isActive()) return;
        net.minecraft.client.player.ClientInput self = (net.minecraft.client.player.ClientInput) (Object) this;
        net.minecraft.world.entity.player.Input p = self.keyPresses;
        if (p == null || (!p.jump() && !p.shift())) return;
        self.keyPresses = new net.minecraft.world.entity.player.Input(
                p.forward(), p.backward(), p.left(), p.right(), false, false, p.sprint());
    }
}
