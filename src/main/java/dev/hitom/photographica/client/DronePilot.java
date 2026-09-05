package dev.hitom.photographica.client;

import dev.hitom.photographica.component.CameraSettings;
import dev.hitom.photographica.component.LensKind;
import dev.hitom.photographica.entity.DroneEntity;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.item.FilmCameraItem;
import dev.hitom.photographica.network.UpdateArmorStandCameraPayload;
import dev.hitom.photographica.network.UpdateDronePositionPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/**
 * Flies a drone's camera the same way snapmatica's Freecam does: the PILOT's own view detaches
 * from their body and flies free, with {@code thirdPerson} forced true so the player's own
 * body stays visible in the shot — a drone selfie, not a first-person cockpit view.
 *
 * <p>Unlike a pure client-side freecam, this is tied to a real {@link DroneEntity} — every tick
 * the tracked position is sent to the server (see {@link UpdateDronePositionPayload}), which
 * moves the entity to match, so every other player sees the drone actually fly where the pilot
 * is looking. The camera itself is repositioned by {@link dev.hitom.photographica.mixin.client.CameraMixin}
 * cancelling {@code Camera.update()}, exactly like the old Freecam's mixin did.
 */
@Environment(EnvType.CLIENT)
public final class DronePilot {
    private DronePilot() {}

    private static boolean active = false;
    private static int droneEntityId = -1;
    /**
     * Tripod-style lock (the "C" key): the camera holds exactly where it was left — no WASD
     * flight, no mouse look — while the player's own input is handed back so they can walk
     * into frame and look around normally, same as snapmatica's Freecam tripod lock. Toggled
     * independently of {@link #active} itself; "C" again resumes flying from wherever it was
     * left, and the "Esc"-triggered full release in {@link #tick} works the same whether
     * locked or flying.
     */
    private static boolean locked = false;

    private static Vec3d pos = Vec3d.ZERO;
    private static Vec3d prevPos = Vec3d.ZERO;
    private static float yaw, pitch;

    /** The player's real {@link Input} while not piloting, to restore on exit. */
    private static Input savedInput;
    private static float lastHealth;

    // Inertia flight, matching snapmatica's own drone mode: WASD/Space/Shift are thrust, not
    // velocity — the camera accelerates and coasts to a stop rather than snapping to a fixed
    // speed the instant a key is pressed or released. Unlike snapmatica, there is no Ctrl
    // speed boost — snapmatica is a convenience tool (get the shot fast); photographica leans
    // the other way on purpose (a real drone doesn't have a "go 3x faster" button either).
    private static Vec3d velocity = Vec3d.ZERO;
    private static final double ACCEL = 0.06;
    private static final double MAX_SPEED = 1.2;
    private static final double DRAG = 0.90;
    /** How hard the vertical axis brakes with no Space/Shift held — the altitude hold; a real
     *  drone holds height instead of drifting on the same drag as horizontal flight. */
    private static final double VERTICAL_HOLD_DRAG = 0.55;

    // Bank (roll): a real quadcopter tilts its whole airframe toward whichever way it's
    // accelerating — the rotors thrust straight up relative to the body, so leaning is what
    // actually pushes it sideways. Purely a render-time tilt of the pilot's own view (see
    // CameraMixin) — it never touches yaw/pitch/look direction, just banks the horizon,
    // exactly like real drone footage.
    private static float bank = 0f;
    private static float prevBank = 0f;
    private static final float MAX_BANK_DEG = 25f;
    /** Degrees of bank per (block/tick) of lateral speed, before the max-bank clamp. */
    private static final float BANK_GAIN = 30f;
    /** How quickly the bank angle eases toward its target each tick (0..1) — a real airframe
     *  doesn't snap to a lean instantly, it rolls into it. */
    private static final float BANK_EASE = 0.15f;

    // Unlike snapmatica's freecam (a pure spectator-style view with no collision), this one
    // bumps against blocks — sized to match DroneEntity's actual registered hitbox (see
    // ModEntities), not guessed. Checked per-axis (not a single swept box) so sliding along a
    // surface still works instead of stopping dead the instant any one axis is blocked.
    //
    // Critically, this box is anchored at `pos` the same way vanilla anchors every entity's
    // own position — Y is the FEET, not the center — because `pos` is exactly what gets sent
    // to the real DroneEntity via UpdateDronePositionPayload and interpreted there as feet.
    // Testing a box centered on `pos` (as an earlier version did) meant this collision check
    // was probing a different vertical slice of the world than where the entity's real feet
    // would end up: flying down until the CENTER of a small box grazed the floor left the real
    // feet-anchored entity sitting HALF_EXTENT below that, embedded in the block underneath —
    // exactly the "still clips into the ground, feet never actually touch down" symptom.
    private static final double HALF_WIDTH = 0.44;
    private static final double HEIGHT = 0.28;

    /** {@code pos} is the drone's FEET (see above) — rendering the camera exactly there sits
     *  the lens literally on the ground the instant it lands (or hovers close to it), which
     *  read as an x-ray/culling glitch (the world renderer isn't expecting a camera sitting
     *  ON a block's own surface) rather than "the drone is grounded". Real cameras aren't
     *  mounted at a drone's landing-gear height either — this offsets the RENDERED view only
     *  (see CameraMixin), matching roughly where the model's own camera_mount cuboid sits;
     *  collision and the position synced to the real entity both stay feet-anchored. */
    public static final double CAMERA_EYE_HEIGHT = 0.15;

    private static long lastSyncMs = 0;
    private static final long SYNC_INTERVAL_MS = 50; // 20/s, matches the tick rate
    /** Throttles AF settings round-trips the same way {@link #lastSyncMs} throttles position
     *  sync — a focus-distance readout doesn't need to be fresher than 20/s either. */
    private static long lastAfSyncMs = 0;

    // Radio range: full strength (100) at 0 distance under a clear sky, dropping linearly to 0
    // right at FULL_RANGE blocks — and every opaque block sampled along the straight line to
    // the drone eats RANGE_PENALTY_PER_BLOCK off that ceiling, the same real-RF-line-of-sight
    // idea DroneRemoteItem's initial connect check already used, just now run continuously so
    // flying out of range (or behind enough walls) mid-flight is caught, not just at connect
    // time.
    /** Mirrors {@link DroneEntity#MAX_REMOTE_RANGE} — the server enforces the same number
     *  authoritatively, this side is the local prediction of it. */
    private static final double FULL_RANGE = DroneEntity.MAX_REMOTE_RANGE;
    private static final double RANGE_PENALTY_PER_BLOCK = 10.0;
    private static final int LOW_SIGNAL_THRESHOLD = 50;
    /** How often {@link #signal} itself actually gets recomputed (the opaque-block raycast
     *  isn't free) — {@code signal} is still read every tick in between, it just holds its
     *  last checked value rather than being recalculated 20x/s. The hard boundary clamp on
     *  movement (see the flight code below) is what actually guarantees the 128m wall can't be
     *  crossed; this interval is purely about how often the reactive HUD/crash-trigger value
     *  itself gets refreshed. */
    private static final long SIGNAL_CHECK_INTERVAL_MS = 150;
    private static long lastSignalCheckMs = 0;
    /** Grace window after signal hits zero before the drone actually crashes — a real link
     *  drop-out is rarely instantaneous-and-permanent, so a brief window to reconnect (holding
     *  position, not flying blind) reads as more realistic than crashing the instant one tick
     *  reads zero. 20 ticks = 1 second. */
    private static final int SIGNAL_LOST_GRACE_TICKS = 20;
    private static int signal = 100;
    private static boolean lowSignalWarned = false;
    /** >0 while signal has been at zero for fewer than {@link #SIGNAL_LOST_GRACE_TICKS} ticks
     *  — movement is frozen (not flying blind), but nothing's crashed yet. Reset to 0 the
     *  instant signal returns. */
    private static int noSignalTicks = 0;

    public static boolean isActive() {
        return active;
    }

    /** Current signal strength, 0 (no link) to 100 (full bars) — for HUD display. */
    public static int getSignal() {
        return signal;
    }

    /** >0 while riding out {@link #SIGNAL_LOST_GRACE_TICKS} before an actual crash — the HUD
     *  pins noise to maximum for this whole window rather than scaling it off {@link #signal}
     *  (which is already 0 and wouldn't visually distinguish "just dipped" from "about to
     *  crash"). */
    public static int getNoSignalTicks() {
        return noSignalTicks;
    }

    /** The outer search radius a remote should even bother scanning at — matches
     *  {@link #FULL_RANGE}, the distance signal reaches exactly 0 at with a perfectly clear
     *  line of sight. */
    public static double getFullRange() {
        return FULL_RANGE;
    }

    public static boolean isLocked() {
        return locked;
    }

    public static int droneEntityId() {
        return droneEntityId;
    }

    public static Vec3d getPos() { return pos; }
    public static Vec3d getPrevPos() { return prevPos; }
    public static float getYaw() { return yaw; }
    public static float getPitch() { return pitch; }
    public static float getBank() { return bank; }
    public static float getPrevBank() { return prevBank; }

    /** Focal length of whatever camera is currently mounted on the piloted drone, or -1 if
     *  none/not piloting — used to drive the live viewfinder's FOV (see GameRendererMixin) the
     *  same way {@link #getSignal()} etc. expose per-tick drone state read elsewhere. */
    public static int getMountedFocalLength(MinecraftClient client) {
        if (!active || client.world == null) return -1;
        if (!(client.world.getEntityById(droneEntityId) instanceof DroneEntity drone)) return -1;
        ItemStack mounted = drone.getEquippedCamera();
        if (mounted.isEmpty()) return -1;
        boolean isFilmCam = mounted.getItem() instanceof FilmCameraItem;
        if (!isFilmCam && !(mounted.getItem() instanceof CameraItem)) return -1;
        CameraSettings s = isFilmCam ? FilmCameraItem.getSettings(mounted) : CameraItem.getSettings(mounted);
        return LensKind.hasLens(s.lensType()) ? s.focalLengthMm() : -1;
    }

    public static void toggle(MinecraftClient client, DroneEntity drone) {
        if (client.player == null) return;
        if (active && droneEntityId == drone.getId()) {
            deactivate(client);
        } else {
            if (active) deactivate(client); // switching drones: release the old one first
            activate(client, drone);
        }
    }

    /**
     * "C": freezes (or un-freezes) the camera in place and swaps the player's input
     * accordingly. Locked: WASD/mouse stop steering the camera and go back to the player, who
     * can now walk into the still-fixed shot. Unlocked: the reverse — flying resumes from
     * wherever the camera was left, input blanked again exactly like on activation.
     */
    public static void toggleLock(MinecraftClient client) {
        if (!active || client.player == null) return;
        locked = !locked;
        if (locked) {
            if (savedInput != null) client.player.input = savedInput;
        } else {
            client.player.input = new Input();
        }
    }

    /** "Esc": fully exits piloting (flying or locked) back to the player's own first-person
     *  view. Distinct from {@link #toggleLock} — this hands the camera itself back too, not
     *  just player movement. */
    public static void releaseToPlayer(MinecraftClient client) {
        if (active) deactivate(client);
    }

    /** Continuous zoom (24-200mm) driven by scroll while piloting — "just zooms the screen",
     *  not a real lens: no aperture change, no bokeh. ~8% per scroll tick reads as a smooth
     *  digital zoom rather than snapping between fixed stops. */
    public static void adjustFocalLength(MinecraftClient client, int dir) {
        if (!active || client.world == null) return;
        if (!(client.world.getEntityById(droneEntityId) instanceof DroneEntity drone)) return;
        ItemStack mounted = drone.getEquippedCamera();
        if (mounted.isEmpty()) return;
        boolean isFilmCam = mounted.getItem() instanceof FilmCameraItem;
        if (!isFilmCam && !(mounted.getItem() instanceof CameraItem)) return;
        CameraSettings s = isFilmCam ? FilmCameraItem.getSettings(mounted) : CameraItem.getSettings(mounted);
        int cur = s.focalLengthMm();
        int next = dir > 0
                ? Math.min(LensKind.DRONE_FOCAL_MAX, Math.max(cur + 1, Math.round(cur * 1.08f)))
                : Math.max(LensKind.DRONE_FOCAL_MIN, Math.min(cur - 1, Math.round(cur / 1.08f)));
        // Hard detent right at the optical/digital boundary — a real lens with a hybrid zoom
        // range clicks to a stop there rather than gliding straight through, so crossing from
        // "real" zoom into "digital" zoom (or back) always takes a deliberate extra scroll from
        // exactly 70mm, not a single continuous motion that blows past it.
        int opticalMax = LensKind.DRONE_FOCAL_OPTICAL_MAX;
        if (cur != opticalMax) {
            if (cur < opticalMax && next > opticalMax) next = opticalMax;
            else if (cur > opticalMax && next < opticalMax) next = opticalMax;
        }
        if (next == cur) return;
        CameraSettings updated = new CameraSettings(2.8f, s.shutterSpeedIdx(), s.iso(), s.focusDistance(), next,
                LensKind.DRONE_ZOOM, s.filmType(), s.remainingShots(), s.exposureMode(), s.focusMode(),
                s.autoWind(), s.timerSeconds(), s.motionBlur(), s.focusPeaking());
        ClientPlayNetworking.send(new UpdateArmorStandCameraPayload(droneEntityId, updated));
    }

    /** Fires the mounted camera directly — the fast path a real remote's shutter button gives
     *  you, instead of needing to open the full settings screen and click 撮影 (still available
     *  via the settings key, for adjusting ISO/shutter/self-timer or reviewing the SD card). */
    public static void triggerCapture(MinecraftClient client) {
        if (!active || client.world == null) return;
        if (!(client.world.getEntityById(droneEntityId) instanceof DroneEntity drone)) return;
        ItemStack mounted = drone.getEquippedCamera();
        if (mounted.isEmpty()) return;
        PhotoCapture.triggerArmorStandCapture(droneEntityId, mounted);
    }

    private static void activate(MinecraftClient client, DroneEntity drone) {
        Camera camera = client.gameRenderer.getCamera();
        // The camera starts flying from where the DRONE actually is, not the player's own eye
        // position — seeding pos from the player would teleport the drone entity itself to the
        // player the instant the next position sync sent it there, visibly "yanking" it across
        // the map to wherever the pilot was standing.
        //? if >=1.21.11 {
        /*pos = drone.getEntityPos();
        *///?} else {
        pos = drone.getPos();
        //?}
        prevPos = pos;
        yaw = camera.getYaw();
        pitch = camera.getPitch();
        velocity = Vec3d.ZERO;
        bank = prevBank = 0f;
        droneEntityId = drone.getId();
        signal = 100;
        lastSignalCheckMs = 0;
        lowSignalWarned = false;
        noSignalTicks = 0;

        // Same reasoning as the old Freecam: blank WASD/jump/sneak so the body doesn't also
        // walk off wherever the drone flies, but leave gravity/momentum/collision alone so a
        // player who activates this mid-air doesn't just hang there.
        savedInput = client.player.input;
        client.player.input = new Input();
        lastHealth = client.player.getHealth();
        active = true;
    }

    private static void deactivate(MinecraftClient client) {
        // Zoom resets to the wide end on exit — real cameras don't remember a zoom position
        // across power cycles either, and leaving it wherever it was left made the NEXT flight
        // (this drone or a different one) silently start pre-zoomed with no visual cue why.
        resetZoom(client);
        active = false;
        locked = false;
        droneEntityId = -1;
        if (client.player != null && savedInput != null) {
            client.player.input = savedInput;
        }
        savedInput = null;
    }

    private static void resetZoom(MinecraftClient client) {
        if (client.world == null) return;
        if (!(client.world.getEntityById(droneEntityId) instanceof DroneEntity drone)) return;
        ItemStack mounted = drone.getEquippedCamera();
        if (mounted.isEmpty()) return;
        boolean isFilmCam = mounted.getItem() instanceof FilmCameraItem;
        if (!isFilmCam && !(mounted.getItem() instanceof CameraItem)) return;
        CameraSettings s = isFilmCam ? FilmCameraItem.getSettings(mounted) : CameraItem.getSettings(mounted);
        if (s.focalLengthMm() == LensKind.DRONE_FOCAL_MIN) return;
        CameraSettings reset = new CameraSettings(2.8f, s.shutterSpeedIdx(), s.iso(), s.focusDistance(),
                LensKind.DRONE_FOCAL_MIN, LensKind.DRONE_ZOOM, s.filmType(), s.remainingShots(),
                s.exposureMode(), s.focusMode(), s.autoWind(), s.timerSeconds(), s.motionBlur(), s.focusPeaking());
        ClientPlayNetworking.send(new UpdateArmorStandCameraPayload(droneEntityId, reset));
    }

    /**
     * Hard reset of all piloting state, for leaving a world (see {@code PhotographicaClient}'s
     * disconnect hook). Every field here is static and therefore survives a disconnect, and
     * {@link #active} staying true into the NEXT world is close to unrecoverable: the camera
     * mixin keeps forcing the view to a stale position in a world that no longer has that
     * drone, mouse look and every click stay cancelled, and the hotbar/health/block-outline
     * stay hidden. Deliberately touches no player object — the player is normally already gone
     * by the time this runs, which is exactly why {@link #deactivate} can't be reused here.
     */
    public static void reset() {
        active = false;
        locked = false;
        droneEntityId = -1;
        savedInput = null;
        pos = prevPos = Vec3d.ZERO;
        velocity = Vec3d.ZERO;
        bank = prevBank = 0f;
        yaw = pitch = 0f;
        signal = 100;
        lowSignalWarned = false;
        noSignalTicks = 0;
        lastHealth = 0f;
    }

    public static void onMouseLook(double dx, double dy) {
        if (!active || locked) return;
        yaw += (float) (dx * 0.15);
        pitch = MathHelper.clamp((float) (pitch + dy * 0.15), -90.0f, 90.0f);
    }

    public static void tick(MinecraftClient client) {
        if (!active || client.player == null) return;

        // Esc: the pause menu opening is treated as "get me all the way out" — the camera
        // settings screens (opened via the settings key while piloting) are deliberately NOT
        // included here, so checking exposure mid-flight doesn't kick the pilot out.
        if (client.currentScreen instanceof net.minecraft.client.gui.screen.GameMenuScreen) {
            deactivate(client);
            return;
        }

        prevPos = pos;
        prevBank = bank;

        // Vanilla's sprint-key check runs independently of player.input (a direct keybinding
        // poll, not something blanking player.input to a fresh Input() stops) — holding Ctrl
        // from before activating, or out of habit while flying, was leaving the player flagged
        // as sprinting, which fed vanilla's own FOV widening into the drone's lens FOV on top
        // of it (see GameRendererMixin). The drone has no sprint concept of its own at all, so
        // this is forced off every tick regardless of how it got set.
        if (client.player.isSprinting()) {
            client.player.setSprinting(false);
        }

        // Signal is measured against the client's OWN flown position, not the drone entity's,
        // and this whole block deliberately runs whether or not that entity is currently
        // loaded. It used to live inside the `getEntityById(...) instanceof DroneEntity` check
        // below — which meant that the moment the drone passed the server's entity tracking
        // range and untracked, getEntityById returned null and the ENTIRE range system silently
        // stopped running: no attenuation, no signal loss, no crash, unlimited range. `pos` is
        // also the better measurement anyway: it's exactly what the pilot is flying, with none
        // of the entity's interpolation lag.
        //
        // Checked before the locked branch too — being locked hands the player's own movement
        // back, so they can perfectly well walk themselves out of range of a camera they left
        // sitting still, and a real link doesn't care whether the operator is actively
        // steering or just standing there watching.
        if (client.world != null) {
            long nowSignal = System.currentTimeMillis();
            if (nowSignal - lastSignalCheckMs >= SIGNAL_CHECK_INTERVAL_MS) {
                lastSignalCheckMs = nowSignal;
                signal = computeSignal(client, client.player.getEyePos(), pos);
            }
            if (signal <= 0) {
                // A real drone doesn't gently glide to a stop the instant its link drops — it
                // falls, starting immediately, not after a delay. DroneEntity#startFalling()
                // (triggered server-side via this payload) turns its physics back on and lets
                // gravity do the rest independently of whatever the pilot's view does next; it
                // survives landing (see DroneEntity#tick), so there's still something to
                // reconnect to. noSignalTicks itself is now purely "how long has the PILOT been
                // unable to fly it" — the grace window below is about giving up on control, not
                // about delaying the fall.
                if (noSignalTicks == 0) {
                    // Hands over the velocity it was flying at, so the airframe coasts on under
                    // its own momentum rather than stopping dead and dropping straight down.
                    ClientPlayNetworking.send(new dev.hitom.photographica.network.DroneSignalLostPayload(
                            droneEntityId, velocity.x, velocity.y, velocity.z));
                }
                noSignalTicks++;
                if (noSignalTicks >= SIGNAL_LOST_GRACE_TICKS) {
                    deactivate(client);
                    return;
                }
                // Still within the grace window: hold position (prevPos/prevBank were already
                // resynced above, same anti-jitter reasoning as the locked branch below) and
                // skip movement entirely — a link that's flatlined has nothing to fly on. The
                // drone itself may be falling independently of this frozen camera the whole time.
                lastHealth = client.player.getHealth();
                bank += (0f - bank) * BANK_EASE;
                return;
            }
            if (noSignalTicks > 0) {
                // Recovered inside the grace window — resume flying from wherever the drone
                // actually ended up (it coasted on, and may have landed, while the link was
                // down), not from the frozen pre-outage position, or the camera would visibly
                // snap to catch up on the very next synced position packet. Only possible when
                // the entity is loaded; otherwise the local position stands, and the next sync
                // packet puts the entity back onto it.
                noSignalTicks = 0;
                if (client.world.getEntityById(droneEntityId) instanceof DroneEntity recovered) {
                    //? if >=1.21.11 {
                    /*Vec3d recoveredPos = recovered.getEntityPos();
                    *///?} else {
                    Vec3d recoveredPos = recovered.getPos();
                    //?}
                    pos = recoveredPos;
                    prevPos = recoveredPos;
                }
                velocity = Vec3d.ZERO;
                client.player.sendMessage(net.minecraft.text.Text.literal("📡 電波を再び拾いました"), true);
            }
            if (signal <= LOW_SIGNAL_THRESHOLD) {
                if (!lowSignalWarned) {
                    lowSignalWarned = true;
                    client.player.sendMessage(net.minecraft.text.Text.literal("⚠ 電波が弱くなっています"), true);
                }
            } else {
                lowSignalWarned = false;
            }
        }

        // Continuous autofocus for the mounted camera — mirrors the same AutoCamera call a
        // handheld camera gets every sneaking frame; a piloted drone's entire flight IS that
        // "actively framing a shot" state, there's no separate trigger for it. Unlike the
        // signal check above this genuinely does need the entity (the camera and its settings
        // live in the entity's tracked data), so it simply doesn't run while untracked — a
        // stale focus distance is a cosmetic problem, not a correctness one.
        if (client.world != null && client.world.getEntityById(droneEntityId) instanceof DroneEntity drone) {
            ItemStack mounted = drone.getEquippedCamera();
            boolean isFilmCam = mounted.getItem() instanceof FilmCameraItem;
            if (!mounted.isEmpty() && (isFilmCam || mounted.getItem() instanceof CameraItem)) {
                CameraSettings s = isFilmCam ? FilmCameraItem.getSettings(mounted) : CameraItem.getSettings(mounted);
                if (s.focusMode() != CameraSettings.FOCUS_MF) {
                    CameraSettings focused = AutoCamera.snapFocusFromDroneRay(client,
                            RenderCamera.pos(client), RenderCamera.look(client), s);
                    long nowAf = System.currentTimeMillis();
                    if (focused.focusDistance() != s.focusDistance() && nowAf - lastAfSyncMs >= SYNC_INTERVAL_MS) {
                        lastAfSyncMs = nowAf;
                        ClientPlayNetworking.send(new UpdateArmorStandCameraPayload(droneEntityId, focused));
                    }
                }
            }
        }

        float hp = client.player.getHealth();
        if (hp < lastHealth) {
            // Same safety net as the old Freecam: something hit the player, hand control back
            // immediately — checked BEFORE the locked branch below and regardless of it, since
            // even locked mode only frees up the player's own MOVEMENT; their actual view is
            // still the drone's frozen footage, not their own eyes, so they're just as unable
            // to see (and react to) whatever is attacking them as mid-flight.
            client.player.sendMessage(net.minecraft.text.Text.literal("⚠ ダメージを受けたため操縦を中断しました"), true);
            deactivate(client);
            return;
        }
        lastHealth = hp;
        if (locked) {
            // Position/yaw/pitch genuinely freeze here — that's the whole point of the lock.
            // Bank doesn't: a real drone levels itself out the instant it stops accelerating
            // sideways, so leaving it pinned at whatever angle it happened to be mid-turn read
            // as visibly wrong (and never had a way to recover, since nothing but active
            // sideways thrust below ever moves bank back toward 0).
            bank += (0f - bank) * BANK_EASE;
            return; // camera holds exactly here; player has real input back
        }

        //? if >=1.21.10 {
        /*net.minecraft.client.util.Window window = client.getWindow();
        *///?} else {
        long window = client.getWindow().getHandle();
        //?}

        boolean w = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_W);
        boolean s = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_S);
        boolean a = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_A);
        boolean d = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_D);
        boolean up = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_SPACE);
        boolean down = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_SHIFT);

        // Level flight, like Creative mode — not "fly wherever the camera is looking" (that
        // would send the drone climbing/diving every time the shot tilts up or down, which no
        // real drone does: its rotors thrust along the airframe, not the gimbal/camera's own
        // pitch). W/S/A/D stay on the horizontal plane regardless of pitch; only Space/Shift
        // (handled below) move it vertically.
        double yawRad = Math.toRadians(yaw);
        Vec3d forward = new Vec3d(-Math.sin(yawRad), 0, Math.cos(yawRad));
        Vec3d right = new Vec3d(-Math.cos(yawRad), 0, -Math.sin(yawRad));

        double accelMag = ACCEL;
        Vec3d thrust = Vec3d.ZERO;
        if (w) thrust = thrust.add(forward);
        if (s) thrust = thrust.subtract(forward);
        if (d) thrust = thrust.add(right);
        if (a) thrust = thrust.subtract(right);
        if (thrust.lengthSquared() > 0) {
            velocity = velocity.add(thrust.normalize().multiply(accelMag));
        }

        // Altitude hold: with no vertical input, the vertical component brakes hard rather
        // than drifting on the same drag as horizontal flight.
        double vy = velocity.y;
        if (up) vy += accelMag;
        else if (down) vy -= accelMag;
        else vy *= VERTICAL_HOLD_DRAG;
        velocity = new Vec3d(velocity.x, vy, velocity.z);

        velocity = velocity.multiply(DRAG);

        if (velocity.length() > MAX_SPEED) {
            velocity = velocity.normalize().multiply(MAX_SPEED);
        }

        // Bank into the direction of horizontal travel — positive lateral speed (toward
        // "right") banks the right side down, same as a real quadcopter leaning to
        // strafe. Forward/back speed doesn't bank; only the sideways component does, since
        // pitching the nose is a separate axis a camera drone's gimbal already keeps level.
        double lateralSpeed = velocity.dotProduct(right);
        float targetBank = MathHelper.clamp((float) (-lateralSpeed * BANK_GAIN), -MAX_BANK_DEG, MAX_BANK_DEG);
        bank += (targetBank - bank) * BANK_EASE;

        if (velocity.lengthSquared() > 1.0e-8) {
            Vec3d newPos = moveWithCollision(client, pos, velocity);
            // A collision that stopped an axis dead should also kill that axis's momentum —
            // otherwise the drone keeps "pushing" into the wall it just slid along, and the
            // instant it clears the obstruction the still-full velocity launches it forward.
            if (newPos.x == pos.x) velocity = new Vec3d(0, velocity.y, velocity.z);
            if (newPos.y == pos.y) velocity = new Vec3d(velocity.x, 0, velocity.z);
            if (newPos.z == pos.z) velocity = new Vec3d(velocity.x, velocity.y, 0);
            pos = newPos;
        }

        long now = System.currentTimeMillis();
        if (now - lastSyncMs >= SYNC_INTERVAL_MS) {
            lastSyncMs = now;
            ClientPlayNetworking.send(new UpdateDronePositionPayload(droneEntityId, pos.x, pos.y, pos.z, yaw, pitch, bank));
        }
    }

    /** Applies {@code delta} one axis at a time, dropping whichever axis would land the small
     *  collision box inside a block — the standard "slide along the surface" trick, checked
     *  against the client's own world (no server round-trip needed, this is a camera position,
     *  not the synced entity's own authoritative one). */
    private static Vec3d moveWithCollision(MinecraftClient client, Vec3d from, Vec3d delta) {
        if (client.world == null) return from.add(delta);
        double x = from.x, y = from.y, z = from.z;

        double nx = x + delta.x;
        if (client.world.isSpaceEmpty(boxAt(nx, y, z))) x = nx;

        double ny = y + delta.y;
        if (client.world.isSpaceEmpty(boxAt(x, ny, z))) y = ny;

        double nz = z + delta.z;
        if (client.world.isSpaceEmpty(boxAt(x, y, nz))) z = nz;

        return new Vec3d(x, y, z);
    }

    private static Box boxAt(double x, double y, double z) {
        return new Box(x - HALF_WIDTH, y, z - HALF_WIDTH,
                x + HALF_WIDTH, y + HEIGHT, z + HALF_WIDTH);
    }

    /** 0 (no link) to 100 (full strength) — see {@link #FULL_RANGE}/{@link #RANGE_PENALTY_PER_BLOCK}.
     *  Shared by the initial connect check ({@code DroneRemoteItem}/{@code PhotographicaClient})
     *  and this class's own per-tick monitoring, so "can I connect" and "am I still connected"
     *  are always answering the exact same question the exact same way. */
    public static int computeSignal(MinecraftClient client, Vec3d from, Vec3d to) {
        if (client.world == null) return 0;
        double dist = from.distanceTo(to);
        double effectiveRange = FULL_RANGE - countOpaqueBlocks(client, from, to) * RANGE_PENALTY_PER_BLOCK;
        if (effectiveRange <= 0 || dist > effectiveRange) return 0;
        return (int) Math.round(100.0 * (effectiveRange - dist) / FULL_RANGE);
    }

    /** Samples roughly once per block along the straight line from {@code from} to {@code to},
     *  counting how many distinct opaque blocks it passes through — an approximation (not a
     *  true voxel traversal), but plenty precise for a range penalty. */
    private static int countOpaqueBlocks(MinecraftClient client, Vec3d from, Vec3d to) {
        double dist = from.distanceTo(to);
        int samples = Math.max(1, (int) Math.round(dist));
        int count = 0;
        net.minecraft.util.math.BlockPos last = null;
        for (int i = 1; i <= samples; i++) {
            double t = i / (double) (samples + 1);
            Vec3d p = from.lerp(to, t);
            net.minecraft.util.math.BlockPos bp = net.minecraft.util.math.BlockPos.ofFloored(p);
            if (bp.equals(last)) continue;
            last = bp;
            if (client.world.getBlockState(bp).isOpaque()) count++;
        }
        return count;
    }
}
