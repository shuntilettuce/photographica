package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.ApertureIntegration;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drop jump and sneak while the shutter is open, and nothing else.
 *
 * <p>The exposure is a real interval the photographer moves through, so walking and looking have
 * to keep working. Jump bobs the viewpoint through an arc nobody asked for and sneak drops it
 * half a block, and the exposure REPLAYS them — a hop during a half-second shutter is not a hop
 * in the picture, it is every intermediate height at once.
 *
 * <p>26 names the holder {@code ClientInput} and its record {@code Input}, whose sneak component
 * is called {@code shift}. Rebuilding the record rather than clearing fields: it is immutable,
 * which is the good kind of obstacle.
 */
@Mixin(ClientInput.class)
public class ExposureInputMixin {
    @Inject(method = "tick", at = @At("RETURN"))
    private void snapmatica$dropJumpAndSneakDuringExposure(CallbackInfo ci) {
        if (!ApertureIntegration.isActive()) return;
        ClientInput self = (ClientInput) (Object) this;
        Input p = self.keyPresses;
        if (p == null || (!p.jump() && !p.shift())) return;
        self.keyPresses = new Input(
                p.forward(), p.backward(), p.left(), p.right(), false, false, p.sprint());
    }
}
