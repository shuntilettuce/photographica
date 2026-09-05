package dev.hitom.photographica.item;

import dev.hitom.photographica.entity.DroneEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
//? if >=1.21.4 {
/*import net.minecraft.util.ActionResult;*/
//?} else {
import net.minecraft.util.TypedActionResult;
//?}

/** Right-click to deploy a {@link DroneEntity} a couple of blocks in front of the player.
 *  Unlike the camera items' {@code use()}, spawning has to happen server-side — the client
 *  finds out about the new entity the ordinary way, via ordinary entity tracking. */
public class DroneItem extends Item {
    public DroneItem(Settings settings) {
        super(settings.maxCount(1));
    }

    //? if >=1.21.11 {
    /*@Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient()) deploy((ServerWorld) world, user, stack);
        return ActionResult.SUCCESS;
    }*/
    //?} else if >=1.21.4 {
    /*@Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient) deploy((ServerWorld) world, user, stack);
        return ActionResult.SUCCESS;
    }*/
    //?} else {
    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        if (!world.isClient) deploy((ServerWorld) world, user, stack);
        return TypedActionResult.success(stack, world.isClient);
    }
    //?}

    /** How far in front of the player the drone deploys when nothing's in the way. */
    private static final double DEPLOY_DISTANCE = 2.5;
    /** How far short of a hit wall to stop — the drone's own collision box (see
     *  DronePilot.HALF_WIDTH) still needs clearance, not just its spawn point. */
    private static final double WALL_CLEARANCE = 0.5;

    private static void deploy(ServerWorld world, PlayerEntity user, ItemStack stack) {
        Vec3d eye = user.getEyePos();
        Vec3d look = user.getRotationVec(1.0f);
        Vec3d end = eye.add(look.multiply(DEPLOY_DISTANCE));

        // Unchecked, this placed the drone DEPLOY_DISTANCE dead ahead regardless of what was
        // there — facing a wall from close range spawned it embedded inside the block. A
        // block raycast (same pattern AutoCamera's autofocus already uses) pulls the spawn
        // point back to just short of whatever's actually in the way.
        net.minecraft.util.hit.BlockHitResult hit = world.raycast(new net.minecraft.world.RaycastContext(
                eye, end, net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
                net.minecraft.world.RaycastContext.FluidHandling.NONE, user));

        double dist = (hit != null && hit.getType() != net.minecraft.util.hit.HitResult.Type.MISS)
                ? Math.max(0.5, eye.distanceTo(hit.getPos()) - WALL_CLEARANCE)
                : DEPLOY_DISTANCE;

        Vec3d pos = eye.add(look.multiply(dist));
        DroneEntity drone = new DroneEntity(world, pos.x, pos.y, pos.z);
        drone.setYaw(user.getYaw());
        // A drone item that was broken off an existing airframe (see DroneEntity#damage)
        // carries that airframe's channel with it — restore it before spawning so every remote
        // already paired to it keeps working, instead of a fresh entity silently rolling a new
        // number. A brand-new drone crafted from scratch has no such component and gets the
        // usual lazy random assignment (see DroneEntity#ensureFrequency).
        Integer savedFreq = stack.get(dev.hitom.photographica.component.ModDataComponents.DRONE_FREQUENCY);
        if (savedFreq != null) {
            drone.setFrequency(savedFreq);
        }
        world.spawnEntity(drone);
        stack.decrement(1);
    }
}
