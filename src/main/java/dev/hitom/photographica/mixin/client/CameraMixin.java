package dev.hitom.photographica.mixin.client;

import dev.hitom.photographica.client.DronePilot;
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
