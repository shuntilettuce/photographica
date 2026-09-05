package dev.shunti.snapmatica.client.mixin;
import dev.shunti.snapmatica.client.ApertureIntegration;

import dev.shunti.snapmatica.client.Freecam;
import dev.shunti.snapmatica.client.SnapmaticaClient;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
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
 * <p>{@code update()} also rebuilds the chunk-culling frustum every frame ({@code
 * prepareCullFrustum}, from the position {@code alignWithEntity} just set) — cancelling the
 * whole method at HEAD skipped that too, so the frustum froze at wherever the player was
 * looking the instant freecam took over: flying away left everything outside that original
 * cone — behind, past buildings, the far side of a hill — never submitted for rendering at
 * all, reading as void. Rebuilding it here with the freecam's own position keeps it honest.
 * (FOV/render-distance inside that frustum shape stay whatever they were on the same last
 * real frame — a much smaller inaccuracy than the position/orientation being wrong entirely,
 * and not worth shadowing {@code setupPerspective} and its FOV calculators to also chase.)
 *
 * <p>Also drives {@code detached} on the camera while active — normally forced true: the
 * vanilla level renderer skips drawing the local player's own model specifically when the
 * camera is both attached AND focused on that entity (so you don't see the inside of your own
 * head), and with the camera flown away from the player but {@code detached} left at whatever
 * it was (typically false), that same check kept hiding the player's body from every angle.
 * {@code SnapmaticaClient.freecamHidePlayer} inverts this on purpose, reusing that same
 * vanilla rule to keep the body out of frame instead.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    protected void setPosition(Vec3 pos) {
        throw new AssertionError();
    }

    @Shadow
    protected void setRotation(float yRot, float xRot) {
        throw new AssertionError();
    }

    @Shadow
    private boolean detached;

    @Shadow
    private Matrix4f cachedViewRotMatrix;

    @Shadow
    public Matrix4f getViewRotationMatrix(Matrix4f matrix) {
        throw new AssertionError();
    }

    @Shadow
    private Matrix4f createProjectionMatrixForCulling() {
        throw new AssertionError();
    }

    @Shadow
    private void prepareCullFrustum(Matrix4fc viewRotationMatrix, Matrix4f projectionMatrixForCulling, Vec3 pos) {
        throw new AssertionError();
    }


    @Shadow
    protected void move(float forward, float vertical, float horizontal) {
        throw new AssertionError();
    }

    @Shadow
    public float yRot() { throw new AssertionError(); }

    @Shadow
    public float xRot() { throw new AssertionError(); }

    @Shadow
    public Vec3 position() { throw new AssertionError(); }

    /**
     * Put the viewpoint on one point of the entrance pupil, by MOVING THE CAMERA.
     *
     * <p>The translation belongs in the modelview, where a moving viewpoint belongs and where a
     * shader pack handles it as it handles the player walking. Only the term that holds the
     * focal plane still is a rotation, applied here as a stereo rig's toe-in. See the 1.21.x
     * tree's copy of this file for the measurements that settled the yaw sign.
     */
    @Unique
    private void snapmatica$applyPupilOffset() {
        if (!ApertureIntegration.isActive()) return;

        // While the exposure is being RECORDED the camera is the photographer's -- walk, turn,
        // pan, follow a subject. Holding the viewpoint still through it would rule out the one
        // shot that needs a moving camera.
        if (dev.shunti.snapmatica.client.EntityExposure.isRecording()) return;

        // Replaying it: each sub-frame is placed where the photographer was at ITS instant of
        // the exposure, recorded at the same sub-tick phase as the entities.
        double[] cam = dev.shunti.snapmatica.client.EntityExposure.cameraFor();
        if (cam != null) {
            setPosition(new Vec3(cam[0], cam[1], cam[2]));
            setRotation((float) cam[3], (float) cam[4]);
        } else if (!ApertureIntegration.hasCameraLatch()) {
            Vec3 p = position();
            ApertureIntegration.latchCamera(p.x, p.y, p.z, yRot(), xRot());
        } else {
            setPosition(new Vec3(ApertureIntegration.camX(), ApertureIntegration.camY(),
                    ApertureIntegration.camZ()));
            setRotation(ApertureIntegration.camYaw(), ApertureIntegration.camPitch());
        }

        float ox = ApertureIntegration.pupilOffsetX();
        float oy = ApertureIntegration.pupilOffsetY();
        if (ox == 0f && oy == 0f) return;
        move(0f, oy, ox);

        float focus = ApertureIntegration.latchedFocusBlocks();
        if (focus <= 1e-4f) return;
        final float TO_DEG = (float) (180.0 / Math.PI);
        // The yaw sign is MEASURED, not reasoned -- see the 1.21.x copy.
        float dYaw   = (float) Math.atan(ox / focus) * TO_DEG;
        float dPitch = (float) Math.atan(oy / focus) * TO_DEG;
        setRotation(yRot() - dYaw, xRot() + dPitch);
    }

    /** The pupil offset has to ride the ordinary path too, not only the freecam one. */
    @Inject(method = "update", at = @At("RETURN"))
    private void snapmatica$pupilAfterUpdate(DeltaTracker deltaTracker, CallbackInfo ci) {
        snapmatica$applyPupilOffset();
    }

    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void snapmatica$freecam(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!Freecam.isActive()) return;
        float tickDelta = deltaTracker.getGameTimeDeltaPartialTick(false);
        if (Freecam.isPinActive() || Freecam.isPathPlaying()) {
            // Orbit and camera-path playback both recompute yaw/pitch once per tick, unlike
            // mouse-driven look, which is already smooth because it updates every frame. Left
            // un-interpolated, that one-tick step showed as visible judder swinging around the
            // pin (or stepping along the path). Short-way-round, the same wraparound handling
            // normal entity look uses.
            float yawDiff = ((Freecam.getYaw() - Freecam.getPrevYaw() + 540f) % 360f) - 180f;
            float yaw = Freecam.getPrevYaw() + yawDiff * tickDelta;
            float pitch = Freecam.getPrevPitch()
                    + (Freecam.getPitch() - Freecam.getPrevPitch()) * tickDelta;
            this.setRotation(yaw, pitch);
        } else {
            this.setRotation(Freecam.getYaw(), Freecam.getPitch());
        }
        Vec3 interpPos = Freecam.getPrevPos().lerp(Freecam.getPos(), tickDelta);
        this.setPosition(interpPos);
        this.detached = !SnapmaticaClient.freecamHidePlayer;

        Matrix4f viewRot = this.getViewRotationMatrix(this.cachedViewRotMatrix);
        this.prepareCullFrustum(viewRot, this.createProjectionMatrixForCulling(), interpPos);

        ci.cancel();
    }
}
