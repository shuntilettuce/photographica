package dev.shunti.photographica.mixin.client;

import dev.shunti.photographica.client.DronePilot;
import dev.shunti.photographica.client.PhotoCapture;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
//? if >=1.21.11 {
/*import net.minecraft.world.World;
*///?} else {
import net.minecraft.world.BlockView;
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While {@link DronePilot} is active, replaces the player-follow logic {@code Camera.update()}
 * normally runs every frame with the pilot's own tracked position and orientation, interpolated
 * between ticks — same mechanism the old (pre-entity) Freecam used. {@code thirdPerson} is
 * forced true so the player's own body stays visible in the shot: a drone selfie, not a
 * first-person cockpit view.
 *
 * <p>Also, independently of the drone, moves the camera across the lens's entrance pupil one
 * point at a time while {@link PhotoCapture} is running an aperture-integration burst — see
 * {@link PhotoCapture#isApertureIntegrating()}. Ported from snapmatica's own {@code CameraMixin},
 * whose class doc has the full reasoning for moving the camera (a modelview translation, which
 * every renderer — including a shader pack — already handles the way it handles the player
 * walking) rather than shearing the projection matrix (the textbook accumulation-buffer
 * construction, but one a pack can silently disagree with).
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

    // Camera has no public roll API (setRotation only takes yaw/pitch) — the quaternion field
    // itself is mutable even though the field reference is final, so rolling it post-hoc (see
    // photographica$applyDronePilot) is the only way to bank the view without reimplementing
    // Mojang's own yaw/pitch quaternion math from scratch.
    @Shadow
    private org.joml.Quaternionf rotation;

    // Photographica's minimum target is 1.21.1, already past the boundary where moveBy's
    // descriptor changed from doubles to floats — unlike snapmatica (which still supports
    // 1.20.1) there is only one branch to carry here.
    @Shadow
    protected void moveBy(float forward, float vertical, float horizontal) {
        throw new AssertionError();
    }

    //? if >=1.21.11 {
    /*@Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void photographica$dronePilot(World world, Entity focusedEntity, boolean thirdPerson,
                                          boolean thirdPersonFront, float tickDelta, CallbackInfo ci) {
        if (!DronePilot.isActive()) return;
        photographica$applyDronePilot(tickDelta);
        ci.cancel();
    }
    *///?} else {
    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void photographica$dronePilot(BlockView world, Entity focusedEntity, boolean thirdPerson,
                                          boolean thirdPersonFront, float tickDelta, CallbackInfo ci) {
        if (!DronePilot.isActive()) return;
        photographica$applyDronePilot(tickDelta);
        ci.cancel();
    }
    //?}

    @Shadow
    public float getYaw() { throw new AssertionError(); }

    @Shadow
    public float getPitch() { throw new AssertionError(); }

    // Camera.getPos was renamed getCameraPos at 1.21.10 (same boundary as snapmatica's own
    // CameraMixin, same mappings toolchain) — a shadow has to carry the name the target
    // actually has, or the mixin fails to apply and the game does not start.
    //? if >=1.21.10 {
    /*@Shadow
    public Vec3d getCameraPos() { throw new AssertionError(); }

    @Unique
    private Vec3d photographica$cameraPos() { return getCameraPos(); }
    *///?} else {
    @Shadow
    public Vec3d getPos() { throw new AssertionError(); }

    @Unique
    private Vec3d photographica$cameraPos() { return getPos(); }
    //?}

    // A burst's several dozen samples take longer than one frame each, and the player is still
    // holding the keys that were keeping them still. Latched once per burst so every sub-frame
    // is placed from the SAME base viewpoint — only the pupil offset differs between them —
    // instead of wherever the player has drifted to by the time a given sample renders.
    @Unique
    private boolean photographica$apertureLatched = false;
    @Unique
    private double photographica$apertureCamX, photographica$apertureCamY, photographica$apertureCamZ;
    @Unique
    private float  photographica$apertureCamYaw, photographica$apertureCamPitch;

    /**
     * Step off the axis onto this frame's point on the pupil, and turn back onto the subject —
     * the accumulation-buffer construction, done as a camera move instead of a projection shear.
     * See the class doc and snapmatica's {@code CameraMixin#snapmatica$offsetOnPupil}, which
     * this mirrors, for the derivation and for why the yaw correction's sign is what it is
     * (measured, not reasoned — Minecraft's yaw runs opposite the camera's own +X).
     */
    @Unique
    private void photographica$applyAperturePupilOffset() {
        if (!PhotoCapture.isApertureIntegrating()) {
            photographica$apertureLatched = false;
            return;
        }
        if (!photographica$apertureLatched) {
            Vec3d p = photographica$cameraPos();
            photographica$apertureCamX = p.x;
            photographica$apertureCamY = p.y;
            photographica$apertureCamZ = p.z;
            photographica$apertureCamYaw = getYaw();
            photographica$apertureCamPitch = getPitch();
            photographica$apertureLatched = true;
        } else {
            setPos(new Vec3d(photographica$apertureCamX, photographica$apertureCamY, photographica$apertureCamZ));
            setRotation(photographica$apertureCamYaw, photographica$apertureCamPitch);
        }

        float ox = PhotoCapture.aperturePupilOffsetX();
        float oy = PhotoCapture.aperturePupilOffsetY();
        if (ox == 0f && oy == 0f) return;
        // moveBy builds (h, v, -f) in camera space rotated by the camera's own orientation, so
        // camera-space +X is right and +Y is up: the pupil offset goes in directly.
        moveBy(0f, oy, ox);

        float focus = PhotoCapture.apertureFocusBlocks();
        if (focus <= 1e-4f) return;
        final float TO_DEG = (float) (180.0 / Math.PI);
        float dYaw   = (float) Math.atan(ox / focus) * TO_DEG;
        float dPitch = (float) Math.atan(oy / focus) * TO_DEG;
        setRotation(getYaw() - dYaw, getPitch() + dPitch);
    }

    //? if >=1.21.11 {
    /*@Inject(method = "update", at = @At("RETURN"))
    private void photographica$aperturePupilOffset(World world, Entity focusedEntity, boolean thirdPerson,
                                        boolean thirdPersonFront, float tickDelta, CallbackInfo ci) {
        photographica$applyAperturePupilOffset();
    }
    *///?} else {
    @Inject(method = "update", at = @At("RETURN"))
    private void photographica$aperturePupilOffset(BlockView world, Entity focusedEntity, boolean thirdPerson,
                                        boolean thirdPersonFront, float tickDelta, CallbackInfo ci) {
        photographica$applyAperturePupilOffset();
    }
    //?}

    @Unique
    private void photographica$applyDronePilot(float tickDelta) {
        this.setRotation(DronePilot.getYaw(), DronePilot.getPitch());
        // setRotation() above just rebuilt `rotation` from yaw/pitch alone (roll 0) — rolling
        // it here, afterward, applies around the camera's OWN current forward axis regardless
        // of which way it's yawed/pitched, which is exactly camera bank/roll.
        float bank = DronePilot.getPrevBank() + (DronePilot.getBank() - DronePilot.getPrevBank()) * tickDelta;
        this.rotation.rotateZ((float) Math.toRadians(bank));
        // pos/prevPos are the drone's FEET (matching the entity's own position convention) —
        // add a small eye-height so the rendered camera sits above the landing gear like a
        // real camera mount, not literally on the ground the drone is standing/hovering on.
        Vec3d feet = DronePilot.getPrevPos().lerp(DronePilot.getPos(), tickDelta);
        this.setPos(feet.add(0, DronePilot.CAMERA_EYE_HEIGHT, 0));
        this.thirdPerson = true;
    }
}
