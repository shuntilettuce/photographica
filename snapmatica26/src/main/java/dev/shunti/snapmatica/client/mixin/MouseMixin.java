package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.CameraScrollHandler;
import dev.shunti.snapmatica.client.Freecam;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseMixin {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void snapmatica$onScroll(long window, double horizontal, double vertical,
                                     CallbackInfo ci) {
        if (CameraScrollHandler.onScroll(vertical)) {
            ci.cancel();
        }
    }

    @Shadow
    private double accumulatedDX;

    @Shadow
    private double accumulatedDY;

    /**
     * While freecam is active, steers it instead of the player and cancels the vanilla path
     * entirely, so the player's own facing stays put — mirroring the player's rotation into
     * the freecam camera (an earlier version of this) meant turning the view also visibly
     * turned the player's body, since yaw/pitch IS the player's body orientation.
     *
     * <p>Locked (tripod mode): the vanilla path runs untouched, so the player looks around
     * normally instead of steering a camera that has stopped moving.
     *
     * <p>Scaling reproduces the vanilla non-smoothed path exactly: {@code sensitivity*0.6+0.2},
     * cubed, times 8 — see {@code MouseHandler.turnPlayer}.
     */
    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void snapmatica$freecamLook(double partialTick, CallbackInfo ci) {
        if (!Freecam.isActive() || Freecam.isLocked()) return;
        Minecraft mc = Minecraft.getInstance();
        double sensitivity = mc.options.sensitivity().get() * 0.6 + 0.2;
        double f = sensitivity * sensitivity * sensitivity;
        double scale = f * 8.0;
        // Holding both mouse buttons while dragging a keyframe hands this same motion to it
        // instead of the camera — the camera's view holds still so the keyframe can be
        // re-aimed without also panning the shot out from under it.
        if (Freecam.isEditingKeyframeOrientation()) {
            Freecam.onMouseLookForKeyframe(accumulatedDX * scale, accumulatedDY * scale);
        } else {
            Freecam.onMouseLook(accumulatedDX * scale, accumulatedDY * scale);
        }
        ci.cancel();
    }

    /**
     * Blocks left/right click while freecam is flying (not locked) — the player is frozen and
     * facing wherever it was left, so an attack or block placement would swing at (or aim
     * from) a direction that has nothing to do with what's on screen. Locked (tripod mode)
     * lifts this: the player has real input and a real facing again, so their clicks are
     * exactly as meaningful as normal. Guarded on no screen being open so this never eats the
     * click that reopens the camera settings to turn freecam back off.
     *
     * <p>All three buttons double as camera-path controls instead of just being eaten — every
     * one was already blocked and otherwise meaningless here. Holding left grabs and drags
     * whatever keyframe the reticle lands on; adding a right-button hold mid-drag switches to
     * re-aiming it instead of moving it (see {@code Freecam.isEditingKeyframeOrientation}).
     * Left/right are tracked by raw press/release rather than a "was pressed" edge check — a
     * hold needs to know the instant the button comes back up, not just the instant it went
     * down. Middle click is add/insert/delete: on a keyframe, removes it; on the curve between
     * two keyframes, inserts a new one exactly there; on empty space, appends a new one at the
     * end — the same reticle-targeting split left click's grab already makes.
     */
    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void snapmatica$blockClicksInFreecam(long window, MouseButtonInfo buttonInfo,
                                                 int action, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (Freecam.isActive() && !Freecam.isLocked() && mc.screen == null) {
            snapmatica$trackButton(buttonInfo.button(), action);
            ci.cancel();
        }
    }

    private void snapmatica$trackButton(int button, int action) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            if (action != GLFW.GLFW_PRESS) return;
            Minecraft mc = Minecraft.getInstance();
            // Curve-insert wins over keyframe-delete: a curve sample sits exactly on every
            // keyframe too, so testing the keyframe first meant aiming anywhere near the path
            // almost always deleted something instead of inserting — the opposite of the
            // safer, easier-to-recover-from default this should have.
            if (Freecam.getTargetedCurveSegment() >= 0) {
                Freecam.insertKeyframeOnCurve(mc);
            } else if (Freecam.getTargetedKeyframeIndex() >= 0) {
                Freecam.deleteTargetedKeyframe(mc);
            } else {
                Freecam.addPathKeyframe(mc);
            }
            return;
        }
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_RELEASE) return; // ignore repeat
        boolean held = action == GLFW.GLFW_PRESS;
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            Freecam.setLeftMouseHeld(held);
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            Freecam.setRightMouseHeld(held);
        }
    }
}
