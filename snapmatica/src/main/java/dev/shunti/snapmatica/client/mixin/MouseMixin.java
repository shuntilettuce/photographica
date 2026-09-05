package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.CameraScrollHandler;
import dev.shunti.snapmatica.client.Freecam;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts mouse scroll events for camera parameter adjustment while the viewfinder is
 * active (player sneaking), and mouse look while freecam is active.
 */
@Mixin(Mouse.class)
public abstract class MouseMixin {

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void snapmatica$onMouseScroll(long window, double horizontal, double vertical,
                                          CallbackInfo ci) {
        if (CameraScrollHandler.onScroll(vertical)) {
            ci.cancel();
        }
    }

    @Shadow
    private double cursorDeltaX;

    @Shadow
    private double cursorDeltaY;

    /**
     * While freecam is active, steers it instead of the player and cancels the vanilla path
     * ({@code player.changeLookDirection}) entirely, so the player's own facing stays put —
     * mirroring the player's rotation into the freecam camera (an earlier version of this)
     * meant turning the view also visibly turned the player's body, since yaw/pitch IS the
     * player's body orientation.
     *
     * <p>Scaling reproduces the vanilla non-smoothed path exactly:
     * {@code sensitivity*0.6+0.2}, cubed, times 8 — see {@code Mouse.updateMouse}. The field
     * layout is the same on every supported version, but {@code updateMouse}'s own parameter
     * list is not: 1.20.1's has no {@code tickDelta} (added later), so the {@code @Inject}
     * itself needs a version split even though the shared body below does not.
     */
    // 1.20.1's updateMouse() takes no tickDelta parameter at all (added later, and unused by
    // this mixin's body anyway) — same shared logic, just a different @Inject signature.
    //? if >=1.21 {
    @Inject(method = "updateMouse", at = @At("HEAD"), cancellable = true)
    private void snapmatica$freecamLook(double tickDelta, CallbackInfo ci) {
        snapmatica$freecamLookImpl(ci);
    }
    //?} else {
    /*@Inject(method = "updateMouse", at = @At("HEAD"), cancellable = true)
    private void snapmatica$freecamLook(CallbackInfo ci) {
        snapmatica$freecamLookImpl(ci);
    }
    *///?}

    private void snapmatica$freecamLookImpl(CallbackInfo ci) {
        // Locked (tripod mode): the vanilla path runs untouched, so the player looks around
        // normally instead of steering a camera that has stopped moving.
        if (!Freecam.isActive() || Freecam.isLocked()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        double sensitivity = mc.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
        double f = sensitivity * sensitivity * sensitivity;
        double scale = f * 8.0;
        // Holding both mouse buttons while dragging a keyframe hands this same motion to it
        // instead of the camera — the camera's view holds still so the keyframe can be
        // re-aimed without also panning the shot out from under it.
        if (Freecam.isEditingKeyframeOrientation()) {
            Freecam.onMouseLookForKeyframe(cursorDeltaX * scale, cursorDeltaY * scale);
        } else {
            Freecam.onMouseLook(cursorDeltaX * scale, cursorDeltaY * scale);
        }
        // 1.20.1's updateMouse() zeroes cursorDeltaX/Y itself at the very end of its own body —
        // exactly the code cancelling here skips — so every subsequent raw cursor-move event
        // (onCursorPos calls updateMouse() once per event, not once per frame, on this version)
        // kept adding onto a delta that never got cleared, snowballing into a runaway spin.
        // >=1.21's onCursorPos resets it independently in the CALLER, right after updateMouse()
        // returns either way (cancelled or not), so only the older branch needs this by hand.
        //? if <1.21 {
        /*cursorDeltaX = 0;
        cursorDeltaY = 0;
        *///?}
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
     * Left/right are tracked by raw press/release rather than {@code wasPressed()} — a hold
     * needs to know the instant the button comes back up, not just the instant it went down.
     * Middle click is add/insert/delete: on a keyframe, removes it; on the curve between two
     * keyframes, inserts a new one exactly there; on empty space, appends a new one at the
     * end — the same reticle-targeting split left click's grab already makes.
     *
     * <p>{@code onMouseButton}'s middle parameter became a {@code MouseInput} record at
     * 1.21.10; on 1.21.1 through 1.21.4 it is still the raw {@code (button, action, mods)}
     * triple.
     */
    //? if >=1.21.10 {
    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void snapmatica$blockClicksInFreecam(long window, net.minecraft.client.input.MouseInput button,
                                                 int action, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (Freecam.isActive() && !Freecam.isLocked() && mc.currentScreen == null) {
            snapmatica$trackButton(button.button(), action);
            ci.cancel();
        }
    }
    //?} else {
    /*@Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void snapmatica$blockClicksInFreecam(long window, int button, int action, int mods,
                                                 CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (Freecam.isActive() && !Freecam.isLocked() && mc.currentScreen == null) {
            snapmatica$trackButton(button, action);
            ci.cancel();
        }
    }
    *///?}

    private void snapmatica$trackButton(int button, int action) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            if (action != GLFW.GLFW_PRESS) return;
            MinecraftClient mc = MinecraftClient.getInstance();
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
