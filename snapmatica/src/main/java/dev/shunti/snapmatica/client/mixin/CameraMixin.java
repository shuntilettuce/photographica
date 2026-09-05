package dev.shunti.snapmatica.client.mixin;

import dev.shunti.snapmatica.client.ApertureIntegration;
import dev.shunti.snapmatica.client.EntityExposure;
import dev.shunti.snapmatica.client.Freecam;
import dev.shunti.snapmatica.client.SnapmaticaClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
//? if >=1.21.11 {
import net.minecraft.world.World;
//?} else {
/*import net.minecraft.world.BlockView;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While {@link Freecam} is active, replaces the player-follow logic {@code Camera.update()}
 * normally runs every frame with the freecam's own tracked position and orientation,
 * interpolated between ticks so flight is smooth at whatever framerate the display runs
 * rather than stepped at the 20 Hz rate movement is actually integrated at.
 *
 * <p>Also drives {@code thirdPerson} on the camera while active — normally forced true: the
 * vanilla world renderer skips drawing the local player's own model specifically when the
 * camera is both first-person AND focused on that entity (so you don't see the inside of your
 * own head), and with the camera flown away from the player but {@code thirdPerson} left at
 * whatever it was (typically false), that same check kept hiding the player's body from every
 * angle. {@code SnapmaticaClient.freecamHidePlayer} inverts this on purpose, reusing that same
 * vanilla rule to keep the body out of frame instead — for footage the player has no business
 * standing in.
 *
 * <p>{@code update()}'s first parameter changed from {@code BlockView} to {@code World} at
 * 1.21.11 — everything below 1.21.11 (1.21.1 through 1.21.10) shares the older signature and
 * the same {@code Camera} shape otherwise ({@code setPos}, {@code setRotation},
 * {@code thirdPerson} all unchanged), so one shared body ({@link #snapmatica$applyFreecam})
 * covers both branches.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    protected void setPos(Vec3d pos) {
        throw new AssertionError();
    }

    @Shadow
    protected void setRotation(float yaw, float pitch) {
        throw new AssertionError();
    }

    @Shadow
    private boolean thirdPerson;

    // moveBy took doubles until 1.21; the pupil offset is a float either way, so the two
    // branches differ only in the shadow's descriptor and both feed snapmatica$moveByPupil.
    //? if >=1.21 {
    @Shadow
    protected void moveBy(float forward, float vertical, float horizontal) {
        throw new AssertionError();
    }

    @Unique
    private void snapmatica$moveByPupil(float vertical, float horizontal) {
        moveBy(0f, vertical, horizontal);
    }
    //?} else {
    /*@Shadow
    protected void moveBy(double forward, double vertical, double horizontal) {
        throw new AssertionError();
    }

    @Unique
    private void snapmatica$moveByPupil(float vertical, float horizontal) {
        moveBy(0.0, vertical, horizontal);
    }
    *///?}

    @Shadow
    public float getYaw() { throw new AssertionError(); }

    @Shadow
    public float getPitch() { throw new AssertionError(); }

    // Camera.getPos was renamed getCameraPos at 1.21.10. A shadow has to carry the name the
    // target actually has, or the mixin fails to apply and the game does not start — which is
    // what four of the six builds were doing before this branch existed.
    //? if >=1.21.10 {
    @Shadow
    public Vec3d getCameraPos() { throw new AssertionError(); }

    @Unique
    private Vec3d snapmatica$cameraPos() { return getCameraPos(); }
    //?} else {
    /*@Shadow
    public Vec3d getPos() { throw new AssertionError(); }

    @Unique
    private Vec3d snapmatica$cameraPos() { return getPos(); }
    *///?}

    /**
     * Put the viewpoint on one point of the entrance pupil, by MOVING THE CAMERA.
     *
     * <p>The first version of this sheared the projection matrix instead, which is the textbook
     * accumulation-buffer construction and is exactly right for a renderer that only rasterises.
     * A shader pack is not one. Photon rebuilds the projection element by element —
     *
     * <pre>  combined_projection_matrix_2 = vec4(gbufferProjection.2.0, gbufferProjection.2.1, ...)
     *   combined_projection_matrix_3 = vec4(0.0, 0.0, ..., 0.0)  </pre>
     *
     * — keeping the shear's compensation term and ZEROING the translation column the shear also
     * writes to, and it feeds that reconstruction to {@code ssao}, {@code gtao}, {@code ssrt},
     * {@code raytracer}, {@code d4_deferred_shading} and the cloud reconstruction. Deferred
     * shading and ambient occlusion running on a projection that has half of a viewpoint change
     * in it is why a burst came back with its brightness swinging by a factor of 1.85, locked to
     * the sign of the pupil offset rather than to anything in the scene: the eye had moved by a
     * centimetre and a half and the picture could not possibly have changed that much.
     *
     * <p>A camera translation belongs in the modelview, which is where every pack already reads
     * "the viewpoint moved" from — it is the same thing that happens when the player walks, and
     * packs handle that perfectly. So the translation goes here, and only the part that keeps the
     * focal plane registered stays in the projection, in {@code m20}/{@code m21}, which Photon
     * reads and preserves. Nothing is written to a slot anyone assumes is empty.
     *
     * <p>{@code moveBy} builds {@code (c, b, -a)} in camera space and rotates it by the camera's
     * own orientation, so camera-space +X is right and +Y is up: the pupil offset goes in
     * directly as {@code (0, offY, offX)}.
     */
    @Unique
    private void snapmatica$applyPupilOffset() {
        if (!ApertureIntegration.isActive()) return;

        // While the exposure is being RECORDED the camera is the photographer's — walk, turn,
        // pan, follow a subject. That interval is the shutter being open, and holding the
        // viewpoint still through it would rule out the one shot that needs a moving camera:
        // track the subject and the background streaks instead of the subject.
        if (EntityExposure.isRecording()) return;

        // Replaying it. Each sub-frame is placed where the photographer was at ITS instant of
        // the exposure — recorded at the same sub-tick phase as the entities, so a tracked
        // subject stays registered against the camera that was following it.
        double[] cam = EntityExposure.cameraFor();
        if (cam != null) {
            setPos(new Vec3d(cam[0], cam[1], cam[2]));
            setRotation((float) cam[3], (float) cam[4]);
        } else if (!ApertureIntegration.hasCameraLatch()) {
            // No recording to replay (older versions, or an exposure that never got a frame).
            // Fall back to the old behaviour: latch where the camera stood and hold it.
            Vec3d p = snapmatica$cameraPos();
            ApertureIntegration.latchCamera(p.x, p.y, p.z, getYaw(), getPitch());
        } else {
            setPos(new Vec3d(ApertureIntegration.camX(), ApertureIntegration.camY(),
                    ApertureIntegration.camZ()));
            setRotation(ApertureIntegration.camYaw(), ApertureIntegration.camPitch());
        }

        snapmatica$offsetOnPupil(ApertureIntegration.pupilOffsetX(),
                                 ApertureIntegration.pupilOffsetY(),
                                 ApertureIntegration.latchedFocusBlocks());
    }

    /**
     * Step off the axis onto one point of the entrance pupil, and turn back onto the subject.
     *
     * <p>Shared by the burst and by the live view, because it is the same optics either way and
     * only the size of the step differs — the whole pupil for a photograph, a fifth of it for
     * the finder.
     */
    @Unique
    private void snapmatica$offsetOnPupil(float ox, float oy, float focus) {
        if (ox == 0f && oy == 0f) return;
        snapmatica$moveByPupil(oy, ox);

        // Then turn back onto the subject — a stereo rig's toe-in, and the second half of what
        // makes this a lens rather than a wobble.
        //
        // Moving the eye across the pupil swings the whole picture; something has to hold the
        // focal plane still or nothing in the photograph is registered and every depth blurs
        // equally. That term used to live in the projection, in m20/m21, on the reasoning that
        // a pack reads those. Measured on the far field of a real burst, where a registered
        // focal plane should have shifted the distance by 11 px: it shifted 0. The projection
        // change was reaching the matrix and not the picture. So it goes where the translation
        // already went and demonstrably works — the modelview.
        //
        // A rotation of dx/F radians moves the image by f_px * dx/F pixels, which IS the
        // compensation, to first order. What it leaves behind is second order: the focal PLANE
        // becomes a focal POINT, with keystone growing off-axis. At the settings this runs at —
        // 4.9 cm of travel against a 2.2 m subject, 0.38 degrees — that error is far below a
        // pixel, and a real rig makes exactly the same trade.
        if (focus <= 1e-4f) return;
        final float TO_DEG = (float) (180.0 / Math.PI);
        // The yaw sign is MEASURED, not reasoned. Both were derived the same way — step off the
        // axis, the subject drifts, turn back onto it — and the vertical came out right while
        // the horizontal came out exactly backwards, because Minecraft's yaw runs the opposite
        // way round from the camera's own +X. Reading the far field of a real burst settled it:
        // with the focal plane registered, the distance should have moved -24.7 px and moved
        // +28 instead, the same size and the wrong way. The vertical predicted -22.3 px and
        // moved -16 — right way, so pitch stands as derived.
        //
        // Left as two separate signs rather than tidied into one because they genuinely are two
        // conventions, and the next person to touch this should see that one of them was only
        // ever settled by measurement.
        float dYaw   = (float) Math.atan(ox / focus) * TO_DEG;
        float dPitch = (float) Math.atan(oy / focus) * TO_DEG;
        setRotation(getYaw() - dYaw, getPitch() + dPitch);
    }

    //? if >=1.21.11 {
    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void snapmatica$freecam(World world, Entity focusedEntity, boolean thirdPerson,
                                    boolean thirdPersonFront, float tickDelta, CallbackInfo ci) {
        if (!Freecam.isActive()) return;
        snapmatica$applyFreecam(tickDelta);
        // Freecam cancels the vanilla body, so a RETURN injection never fires while it is on.
        // The pupil offset has to ride both paths or a burst taken through the freecam would
        // silently have no parallax at all.
        snapmatica$applyPupilOffset();
        ci.cancel();
    }
    //?} else {
    /*@Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void snapmatica$freecam(BlockView world, Entity focusedEntity, boolean thirdPerson,
                                    boolean thirdPersonFront, float tickDelta, CallbackInfo ci) {
        if (!Freecam.isActive()) return;
        snapmatica$applyFreecam(tickDelta);
        // Freecam cancels the vanilla body, so a RETURN injection never fires while it is on.
        // The pupil offset has to ride both paths or a burst taken through the freecam would
        // silently have no parallax at all.
        snapmatica$applyPupilOffset();
        ci.cancel();
    }
    *///?}

    //? if >=1.21.11 {
    @Inject(method = "update", at = @At("RETURN"))
    private void snapmatica$pupilOffset(World world, Entity focusedEntity, boolean thirdPerson,
                                        boolean thirdPersonFront, float tickDelta, CallbackInfo ci) {
        snapmatica$applyPupilOffset();
    }
    //?} else {
    /*@Inject(method = "update", at = @At("RETURN"))
    private void snapmatica$pupilOffset(BlockView world, Entity focusedEntity, boolean thirdPerson,
                                        boolean thirdPersonFront, float tickDelta, CallbackInfo ci) {
        snapmatica$applyPupilOffset();
    }
    *///?}

    @Unique
    private void snapmatica$applyFreecam(float tickDelta) {
        if (Freecam.isPinActive() || Freecam.isPathPlaying()) {
            // Orbit and camera-path playback both recompute yaw/pitch once per tick, unlike
            // mouse-driven look, which is already smooth because it updates every frame. Left
            // un-interpolated, that one-tick step showed as visible judder swinging around the
            // pin (or stepping along the path) — worse the larger the orbit radius or the
            // faster the path moves. Short-way-round, the same wraparound handling normal
            // entity look uses.
            float yawDiff = ((Freecam.getYaw() - Freecam.getPrevYaw() + 540f) % 360f) - 180f;
            float yaw = Freecam.getPrevYaw() + yawDiff * tickDelta;
            float pitch = Freecam.getPrevPitch()
                    + (Freecam.getPitch() - Freecam.getPrevPitch()) * tickDelta;
            this.setRotation(yaw, pitch);
        } else {
            this.setRotation(Freecam.getYaw(), Freecam.getPitch());
        }
        this.setPos(Freecam.getPrevPos().lerp(Freecam.getPos(), tickDelta));
        // Forcing this true is what makes the player visible at all — see the class doc.
        // With SnapmaticaClient.freecamHidePlayer on, leave it false instead, so vanilla's own
        // first-person self-hide rule keeps the body out of frame from every angle.
        this.thirdPerson = !SnapmaticaClient.freecamHidePlayer;
    }
}
