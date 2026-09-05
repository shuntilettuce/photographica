package dev.hitom.photographica.entity;

import dev.hitom.photographica.component.ModDataComponents;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.item.DroneRemoteItem;
import dev.hitom.photographica.registry.ModEntities;
import dev.hitom.photographica.registry.ModItems;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
//? if >=1.21.11 {
/*import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
*///?}

/**
 * A small flying camera platform. No AI, no health track of its own, no passengers — the pilot
 * never rides it, and doesn't fly it by touching it either: touching it with a
 * {@code DroneRemoteItem} pairs the remote to this airframe's {@link #getFrequency() channel},
 * and flying actually happens later from wherever that paired remote is used (see
 * {@code DronePilot}), the same way a real RC handset works. Piloting streams this entity's
 * position back (see {@code UpdateDronePositionPayload}) so every other player sees it actually
 * fly there — PILOT'S OWN VIEW detaches, {@code thirdPerson} forced so the player's own body
 * stays visible in the shot, the way snapmatica's freecam does.
 *
 * <p>The mounted camera is a {@link TrackedData} field so every client (not just the pilot) sees
 * which camera — if any — is aboard, and {@link dev.hitom.photographica.client.render.DroneEntityRenderer}
 * can draw it.
 */
public class DroneEntity extends Entity {
    private static final net.minecraft.entity.data.TrackedData<ItemStack> CAMERA =
            net.minecraft.entity.data.DataTracker.registerData(DroneEntity.class,
                    net.minecraft.entity.data.TrackedDataHandlerRegistry.ITEM_STACK);

    /** Degrees of camera-roll bank, mirrored from the pilot's own client-side lean (see
     *  {@code DronePilot}) so every viewer — not just the pilot's own camera — sees the
     *  airframe visually tilt into a turn. Purely cosmetic; never affects collision or the
     *  synced position/rotation. */
    private static final net.minecraft.entity.data.TrackedData<Float> BANK =
            net.minecraft.entity.data.DataTracker.registerData(DroneEntity.class,
                    net.minecraft.entity.data.TrackedDataHandlerRegistry.FLOAT);

    /** This airframe's radio channel — a real remote doesn't fly whatever drone you happen to
     *  be standing next to, it flies whichever one it's tuned to (see
     *  {@code DroneRemoteItem}/{@link ModDataComponents#DRONE_FREQUENCY}). Assigned once,
     *  randomly, the first time it's touched server-side (see {@link #ensureFrequency()}) —
     *  same lazy-assignment pattern as {@code FaxMachineBlockEntity}'s machine number. Synced
     *  (not just server-side) because the client needs it to answer "is this the drone my
     *  remote is tuned to?" locally, without a round trip, every time the remote is used. */
    private static final net.minecraft.entity.data.TrackedData<Integer> FREQUENCY =
            net.minecraft.entity.data.DataTracker.registerData(DroneEntity.class,
                    net.minecraft.entity.data.TrackedDataHandlerRegistry.INTEGER);

    /**
     * Radio range in blocks: the distance at which signal reaches zero with a completely clear
     * line of sight, before the extra attenuation opaque blocks add along the way (see
     * {@code DronePilot#computeSignal}).
     *
     * <p>Deliberately NOT a hard wall the drone is clamped inside. Nothing stops the airframe
     * from ending up beyond this — losing the link doesn't stop it, it just stops it being
     * STEERED, and whatever momentum it had carries it on (see {@link #startFalling}). Range
     * limits control, not position.
     */
    public static final double MAX_REMOTE_RANGE = 128.0;

    /** True once its remote link has dropped and it's flying on under its own momentum (see
     *  {@link #startFalling}) — normally this entity just sits wherever the last position
     *  sync packet put it, with no physics of its own at all. */
    private boolean falling = false;
    /** Full 3D velocity while uncontrolled, not just a fall speed: the airframe keeps the
     *  momentum it had when the link died and coasts on it. */
    private net.minecraft.util.math.Vec3d fallVelocity = net.minecraft.util.math.Vec3d.ZERO;
    private static final double GRAVITY_PER_TICK = 0.08;
    private static final double TERMINAL_FALL_SPEED = 3.92; // matches vanilla's own terminal velocity
    /** Per-tick horizontal drag while coasting uncontrolled. Higher than the piloted drag in
     *  {@code DronePilot} — with the rotors no longer being actively driven there is nothing
     *  sustaining forward flight, so it bleeds speed noticeably faster than under power. */
    private static final double COAST_DRAG = 0.94;

    public DroneEntity(EntityType<DroneEntity> type, World world) {
        super(type, world);
        this.setNoGravity(true);
        this.noClip = true; // the pilot's own view already does its own (smaller) collision check
        // Standard equipment, not a swappable item — every drone comes with its own built-in
        // camera (see #createBuiltInCamera), no separate "mount a camera" step. Client-side
        // instances start empty and pick this up the ordinary way, via TrackedData sync, same
        // as FREQUENCY (see #ensureFrequency).
        if (!isClientSide()) {
            setEquippedCamera(createBuiltInCamera());
        }
    }

    public DroneEntity(World world, double x, double y, double z) {
        this(ModEntities.DRONE, world);
        setPosition(x, y, z);
    }

    /** Signal lost mid-flight (see {@code DronePilot}/{@code DroneSignalLostPayload}) — turns
     *  real physics back on and lets it drop out of the sky immediately, same as a real drone
     *  entering an uncontrolled fall the instant its link dies, rather than hovering frozen
     *  for a while first. It survives hitting the ground (see {@link #tick()}) — a real drone
     *  fails toward "land wherever it ends up", not toward "explode" — so it's still there,
     *  intact and re-pairable, once a remote comes back in range. */
    public void startFalling(net.minecraft.util.math.Vec3d velocityAtLoss) {
        if (falling || isClientSide()) return;
        falling = true;
        this.noClip = false;
        this.setNoGravity(false);
        // Inherits the velocity it was flying at, rather than dropping straight down from a
        // dead stop: an airframe with real momentum doesn't stop dead the instant its link
        // dies, it coasts on and arcs down out of control. Clamped defensively — this value
        // came over the wire from a client.
        double max = 4.0;
        fallVelocity = new net.minecraft.util.math.Vec3d(
                net.minecraft.util.math.MathHelper.clamp(velocityAtLoss.x, -max, max),
                net.minecraft.util.math.MathHelper.clamp(velocityAtLoss.y, -max, max),
                net.minecraft.util.math.MathHelper.clamp(velocityAtLoss.z, -max, max));
    }

    /** True while a pilot is actively flying it — set by each arriving position sync and
     *  cleared when they stop coming (see {@link #tick()}), which is what makes battery drain
     *  cost flight time rather than wall-clock time. */
    private boolean flying = false;
    private int ticksSinceSync = 0;
    /** Position syncs arrive ~20/s; a few missed ticks is lag, half a second of silence means
     *  nobody is flying it any more. */
    private static final int FLYING_TIMEOUT_TICKS = 10;

    /** Called by the position-sync receiver — marks the airframe as under power this tick. */
    public void markFlying() {
        flying = true;
        ticksSinceSync = 0;
    }

    public boolean isFlying() {
        return flying;
    }

    /**
     * Spends one tick of flight charge. Returns false when the cell is missing or flat, which
     * {@link #tick()} turns into an uncontrolled descent — a drone whose battery dies does not
     * hover politely waiting for a replacement.
     */
    private boolean drainFlightPower() {
        if (++ticksSinceSync > FLYING_TIMEOUT_TICKS) {
            flying = false;
            return true; // nobody flying it; not a power failure, just idle
        }
        // Off until batteries can be recharged — see CameraPower#POWER_ENFORCED. Grounding
        // drones on a flat cell with no way to refill it would just make them disposable.
        if (!dev.hitom.photographica.component.CameraPower.POWER_ENFORCED) return true;
        ItemStack camera = getEquippedCamera();
        if (!dev.hitom.photographica.component.CameraPower.hasBattery(camera)) return false;
        dev.hitom.photographica.component.CameraGear gear =
                dev.hitom.photographica.component.CameraPower.gearOf(camera);
        ItemStack battery = gear.battery().copy();
        if (!dev.hitom.photographica.item.BatteryItem.drain(battery,
                dev.hitom.photographica.item.BatteryItem.DRONE_COST_PER_TICK)) {
            return false;
        }
        ItemStack updated = camera.copy();
        updated.set(dev.hitom.photographica.component.ModDataComponents.CAMERA_GEAR, gear.withBattery(battery));
        setEquippedCamera(updated);
        return true;
    }

    /** A position-sync packet arriving (see {@code UpdateDronePositionPayload}) means a pilot
     *  is actively flying it again — cancels an in-progress fall immediately and switches back
     *  to normal piloted-flight physics (no gravity, no clipping, purely sync-driven), exactly
     *  as if it had never lost signal. Safe to call unconditionally; a no-op when not falling. */
    public void cancelFalling() {
        if (!falling) return;
        falling = false;
        this.noClip = true;
        this.setNoGravity(true);
        fallVelocity = net.minecraft.util.math.Vec3d.ZERO;
    }

    @Override
    public void tick() {
        super.tick();
        if (!isClientSide() && flying && !falling) {
            // Flight is the expensive load, and it's charged per tick rather than per takeoff
            // so hovering costs the same as moving — a hovering quadcopter is still holding
            // itself up. Runs before the falling branch below because running out mid-air has
            // to hand off to exactly the same uncontrolled descent a lost signal produces.
            if (!drainFlightPower()) {
                flying = false;
                startFalling(net.minecraft.util.math.Vec3d.ZERO);
            }
        }
        if (!falling || isClientSide()) return;

        double vy = Math.max(-TERMINAL_FALL_SPEED, fallVelocity.y - GRAVITY_PER_TICK);
        fallVelocity = new net.minecraft.util.math.Vec3d(
                fallVelocity.x * COAST_DRAG, vy, fallVelocity.z * COAST_DRAG);
        move(net.minecraft.entity.MovementType.SELF, fallVelocity);

        // Clipping a wall mid-arc kills the momentum into it, so it doesn't keep grinding along
        // the surface for the rest of the descent.
        if (this.horizontalCollision) {
            fallVelocity = new net.minecraft.util.math.Vec3d(0, fallVelocity.y, 0);
        }

        // Landed on its own before anyone reconnected: stop falling and just sit there, fully
        // intact — back to the exact same "sitting still, waiting for a paired remote" state
        // as a freshly-deployed drone nobody's flown yet.
        if (this.isOnGround() || this.verticalCollision) {
            falling = false;
            this.noClip = true;
            this.setNoGravity(true);
            fallVelocity = net.minecraft.util.math.Vec3d.ZERO;
        }
    }

    // Plain Entity.canHit() defaults to false (unlike LivingEntity, which overrides it true) —
    // without this override the drone is simply invisible to both attack and interact raycasts,
    // so neither left- nor right-click would ever reach onInteract()/damage() at all.
    @Override
    public boolean canHit() {
        return !isRemoved();
    }

    /** {@code Entity.getWorld()} was renamed {@code getEntityWorld()} at 1.21.11. */
    private boolean isClientSide() {
        //? if >=1.21.11 {
        /*return getEntityWorld().isClient();
        *///?} else {
        return getWorld().isClient;
        //?}
    }

    @Override
    protected void initDataTracker(net.minecraft.entity.data.DataTracker.Builder builder) {
        builder.add(CAMERA, ItemStack.EMPTY);
        builder.add(BANK, 0f);
        builder.add(FREQUENCY, -1);
    }

    public int getFrequency() {
        ensureFrequency();
        return this.dataTracker.get(FREQUENCY);
    }

    /** Restores a channel carried over from the drone ITEM that spawned this entity (see
     *  {@code DroneItem#deploy}, {@link #damage}) — breaking and replacing a drone keeps every
     *  remote already paired to it working, instead of silently re-rolling a fresh number that
     *  strands them. Must be called before anything else touches {@link #getFrequency()}
     *  (deploy does this immediately after construction, before the entity is even spawned),
     *  since {@link #ensureFrequency()} only randomizes when the tracked value is still unset. */
    public void setFrequency(int freq) {
        this.dataTracker.set(FREQUENCY, freq);
    }

    private void ensureFrequency() {
        if (this.dataTracker.get(FREQUENCY) < 0 && !isClientSide()) {
            this.dataTracker.set(FREQUENCY, 10 + this.random.nextInt(90)); // 2-digit channel, 10-99
        }
    }

    public ItemStack getEquippedCamera() {
        return this.dataTracker.get(CAMERA);
    }

    public void setEquippedCamera(ItemStack stack) {
        this.dataTracker.set(CAMERA, stack);
    }

    public float getBank() {
        return this.dataTracker.get(BANK);
    }

    public void setBank(float bank) {
        this.dataTracker.set(BANK, bank);
    }

    /** The airframe's standard-equipment camera — fixed 24-200mm f/2.8 AF (see LensKind's
     *  DRONE_ZOOM/DRONE_FOCAL_* constants): a pilot flying with one hand on the stick isn't
     *  fiddling with an aperture ring, they pick a zoom level and let autofocus do the rest
     *  (see DronePilot's continuous AF sync). A plain {@code CameraItem} instance under the
     *  hood purely because that's what the rest of the photo pipeline (SD-card-less save
     *  fallback, CreatePhotoFromDronePayload, etc.) already knows how to point a raycast/screen
     *  at — it's never actually in anyone's inventory. */
    private static ItemStack createBuiltInCamera() {
        ItemStack stack = new ItemStack(ModItems.CAMERA);
        dev.hitom.photographica.component.CameraSettings profiled = new dev.hitom.photographica.component.CameraSettings(
                2.8f, dev.hitom.photographica.component.CameraSettings.DEFAULT.shutterSpeedIdx(),
                dev.hitom.photographica.component.CameraSettings.DEFAULT.iso(),
                dev.hitom.photographica.component.CameraSettings.DEFAULT.focusDistance(),
                dev.hitom.photographica.component.LensKind.defaultFocalLength(dev.hitom.photographica.component.LensKind.DRONE_ZOOM),
                dev.hitom.photographica.component.LensKind.DRONE_ZOOM,
                0, 0, dev.hitom.photographica.component.CameraSettings.EXP_M,
                dev.hitom.photographica.component.CameraSettings.FOCUS_AF,
                false, 0, false, false);
        CameraItem.setSettings(stack, profiled);
        return stack;
    }

    // -------------------------------------------------------------------------
    // Interaction: only the remote matters now — the camera is a standard part of the
    // airframe (see #createBuiltInCamera), not a separate item to equip/remove.
    // -------------------------------------------------------------------------

    // Entity.interact() has returned plain ActionResult since well before 1.21.1 — unlike
    // Item.use(), which only switched from TypedActionResult at 1.21.4. No version split
    // needed here.
    @Override
    public net.minecraft.util.ActionResult interact(PlayerEntity player, Hand hand) {
        return onInteract(player, hand) ? net.minecraft.util.ActionResult.SUCCESS : net.minecraft.util.ActionResult.PASS;
    }

    /** Returns true if the interaction was handled (client and server both take this path so
     *  the client doesn't also try to swing/attack, but every actual state change is guarded
     *  to the correct side). */
    private boolean onInteract(PlayerEntity player, Hand hand) {
        ItemStack held = player.getStackInHand(hand);

        if (held.getItem() instanceof DroneRemoteItem) {
            // Pairing, not piloting — touching the drone with a remote just tunes the remote
            // to this airframe's channel. Actually flying it happens later, from wherever the
            // remote is used (see DroneRemoteItem/DronePilot), same as a real RC handset: you
            // bind it once, then fly from anywhere in range, not by standing at the drone.
            if (!isClientSide()) {
                held.set(ModDataComponents.DRONE_FREQUENCY, getFrequency());
                player.sendMessage(net.minecraft.text.Text.literal(
                        "📡 リモコンをチャンネル " + getFrequency() + " にペアリングしました"), true);
            }
            return true;
        }

        if (!held.isEmpty()) return false; // holding something unrelated — don't eat the click

        // Bare-handed touch no longer flies it — a real drone doesn't respond to being poked,
        // only to its paired remote (see DroneRemoteItem). This is server-authoritative
        // (unlike the old client-only toggle) purely so the hint message can go through the
        // normal chat path; nothing about piloting state is touched here either way.
        if (!isClientSide()) {
            player.sendMessage(net.minecraft.text.Text.literal(
                    "📡 リモコンでチャンネル " + getFrequency() + " を合わせて操縦してください"), true);
        }
        return true;
    }

    // Attacking the drone breaks it and drops the airframe — the camera is standard equipment
    // built into it (see #createBuiltInCamera), not a separate item, so there's nothing extra
    // to drop for it; it's just gone along with the rest of the airframe. The channel carries
    // over onto the dropped item (see #setFrequency/DroneItem#deploy) so every remote already
    // paired to this airframe keeps working after it's placed again, instead of the new entity
    // silently rolling a fresh number and stranding them.
    //? if >=1.21.4 {
    /*@Override
    public boolean damage(net.minecraft.server.world.ServerWorld world,
                          net.minecraft.entity.damage.DamageSource source, float amount) {
        // isRemoved() guards against dropping twice: discard() marks the entity removed
        // immediately but it survives the rest of the tick, so a second damage source landing
        // in that same tick (two explosions, or an explosion plus a projectile) would otherwise
        // re-run this whole method and duplicate the airframe.
        if (isClientSide() || isRemoved()) return false;
        ItemStack drop = new ItemStack(ModItems.DRONE);
        drop.set(ModDataComponents.DRONE_FREQUENCY, getFrequency());
        dropStack(world, drop);
        discard();
        return true;
    }
    *///?} else {
    @Override
    public boolean damage(net.minecraft.entity.damage.DamageSource source, float amount) {
        // See the >=1.21.4 branch: isRemoved() prevents a second damage source in the same tick
        // from duplicating the airframe.
        if (isClientSide() || isRemoved()) return false;
        ItemStack drop = new ItemStack(ModItems.DRONE);
        drop.set(ModDataComponents.DRONE_FREQUENCY, getFrequency());
        dropStack(drop);
        discard();
        return true;
    }
    //?}

    // NOTE: the equipped camera (TrackedData<ItemStack>, see CAMERA above) is sync-only for
    // now — it does not survive a world save/reload. Session-scoped is an acceptable v1
    // limitation (the drone recreates it fresh via createBuiltInCamera on respawn).

    //? if >=1.21.11 {
    /*@Override
    protected void writeCustomData(WriteView view) {
        view.putInt("Frequency", this.dataTracker.get(FREQUENCY));
    }

    @Override
    protected void readCustomData(ReadView view) {
        this.dataTracker.set(FREQUENCY, view.getInt("Frequency", -1));
    }
    *///?} else {
    @Override
    protected void writeCustomDataToNbt(net.minecraft.nbt.NbtCompound nbt) {
        nbt.putInt("Frequency", this.dataTracker.get(FREQUENCY));
    }

    @Override
    protected void readCustomDataFromNbt(net.minecraft.nbt.NbtCompound nbt) {
        this.dataTracker.set(FREQUENCY, nbt.contains("Frequency") ? nbt.getInt("Frequency") : -1);
    }
    //?}
}
