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
 * for freecam ({@code player.input = new Input()}, which neutralises the lot) precisely because
 * the movement is wanted.
 *
 * <p>Rebuilding the record rather than clearing fields: {@code PlayerInput} is immutable, which
 * is the good kind of obstacle — there is no partially-updated state to get wrong.
 */
//? if >=1.21.4 {
@Mixin(net.minecraft.client.input.Input.class)
public class ExposureInputMixin {
    @Inject(method = "tick", at = @At("RETURN"))
    private void snapmatica$dropJumpAndSneakDuringExposure(CallbackInfo ci) {
        if (!ApertureIntegration.isActive()) return;
        net.minecraft.client.input.Input self = (net.minecraft.client.input.Input) (Object) this;
        net.minecraft.util.PlayerInput p = self.playerInput;
        if (p == null || (!p.jump() && !p.sneak())) return;
        self.playerInput = new net.minecraft.util.PlayerInput(
                p.forward(), p.backward(), p.left(), p.right(), false, false, p.sprint());
    }
}
//?} else {
/*@Mixin(net.minecraft.client.input.Input.class)
public class ExposureInputMixin {
    // Two reasons to stand down. Before 1.21.2 there is no PlayerInput and no recorded
    // exposure to move through, so the camera is latched and there is nothing to suppress. At
    // 1.21.2-1.21.3 the record exists but Input.tick still takes (boolean, float): an injector
    // written for the later no-argument form does not merely miss there, it fails to apply and
    // takes the game down with it. Cheap to add a branch for; not worth the risk of guessing
    // the argument names, since all it costs those two versions is that jump and sneak still
    // reach a shot nobody has to hold a key through anyway.
}
*///?}
