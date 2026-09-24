package dev.hitom.photographica.client;

import dev.hitom.photographica.component.CameraSettings;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.item.FilmCameraItem;
import dev.hitom.photographica.network.UpdateCameraSettingsPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Client-side auto-exposure and auto-focus tick handler.
 * Called each client tick while the player is sneaking with a camera (viewfinder active).
 *
 * Exposure modes:
 *   M  – fully manual; nothing is changed here
 *   Av – user sets aperture; auto computes shutter for correct exposure
 *   Tv – user sets shutter; auto computes aperture for correct exposure
 *   P  – auto computes both aperture and shutter using a program line
 *
 * Focus modes:
 *   MF  – manual; nothing is changed here
 *   AF  – snap focusDistance to nearest stop matching PhotoCapture.lastSceneDepthBlocks
 *   MOB – find nearest living entity in a 5° forward cone; snap focus to its distance
 *
 * Packets are sent only when a computed value actually changes stop.
 */
@Environment(EnvType.CLIENT)
public final class AutoCamera {
	private AutoCamera() {}

	private static final List<Float> FOCUS_STOPS = List.of(
			0.3f, 0.5f, 0.7f, 1.0f, 1.2f, 1.5f, 1.8f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f, 4.5f, 5.0f,
			6.0f, 7.0f, 8.0f, 9.0f, 10.0f, 12.0f, 14.0f, 16.0f, 18.0f, 20.0f, 24.0f, 28.0f, 32.0f,
			36.0f, 40.0f, 45.0f, 50.0f, 60.0f, 70.0f, 80.0f, 90.0f, 100.0f, 115.0f, 130.0f, 150.0f,
			170.0f, 200.0f, 230.0f, 270.0f, 300.0f, 350.0f, 400.0f, 450.0f, 500.0f, 600.0f, 700.0f,
			850.0f, 1000.0f, 1200.0f, 1500.0f, 2000.0f, 3000.0f, 5000.0f, 8000.0f, 10000.0f,
			CameraSettings.FOCUS_INFINITY);
	private static final List<Float> APERTURE_STOPS = List.of(
			1.4f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f, 11.0f, 16.0f, 22.0f);
	private static final double[] SHUTTER_SECONDS = {
			30.0, 15.0, 8.0, 4.0, 2.0, 1.0,
			0.5, 0.25, 0.125, 1.0 / 15, 1.0 / 30, 1.0 / 60,
			1.0 / 125, 1.0 / 250, 1.0 / 500, 1.0 / 1000, 1.0 / 2000, 1.0 / 4000
	};

	// cos(5°) — entities must be within this cone of the look direction
	private static final double MOB_CONE_COS = Math.cos(Math.toRadians(5.0));

	// Focus-pull (rack) easing. AF does not snap instantly: focusDistance is eased toward the
	// target stop, so the lens "pulls" focus like a real motor. Racking happens in DIOPTER
	// space (1/distance) rather than log-distance: a real focus ring turns at a constant rate
	// in diopters, and — the reason this matters here — infinity is then a genuine finite
	// value (0 dioptres) instead of a point log-space can only approach asymptotically. That
	// used to force a workaround (ease toward a large-but-finite FAR_ANCHOR "as if" it were
	// the target, snap the label to ∞ separately) which could let the label and the actual
	// racked value disagree while a rack was still in flight. Diopter space has no such
	// asymptote, so the rack can head straight for the real target and the label can read the
	// same value the lens (and the DoF shader) actually uses.
	private static final float RACK_RATE      = 0.28f;   // fraction of remaining dioptres / tick
	private static final float RACK_MAX_STEP  = 0.35f;   // max dioptres / tick (caps rack speed)
	private static final float RACK_SNAP_EPS  = 0.0005f; // lock onto target below this many dioptres
	public  static volatile boolean afAtInfinity = false;

	public static void tick(MinecraftClient mc) {
		if (mc.player == null || mc.world == null) return;
		if (!mc.player.isSneaking()) return;

		ItemStack stack = mc.player.getMainHandStack();
		boolean isFilm = false;
		if (stack.getItem() instanceof FilmCameraItem) {
			isFilm = true;
		} else if (!(stack.getItem() instanceof CameraItem)) {
			stack = mc.player.getOffHandStack();
			if (stack.getItem() instanceof FilmCameraItem) {
				isFilm = true;
			} else if (!(stack.getItem() instanceof CameraItem)) {
				return;
			}
		}

		CameraSettings s = isFilm ? FilmCameraItem.getSettings(stack) : CameraItem.getSettings(stack);
		CameraSettings updated = applyAutoFocus(mc, applyAutoExposure(mc, s), s);

		if (updated == s) return;

		if (isFilm) {
			FilmCameraItem.setSettings(stack, updated);
		} else {
			CameraItem.setSettings(stack, updated);
		}
		ClientPlayNetworking.send(new UpdateCameraSettingsPayload(updated));
	}

	// -------------------------------------------------------------------------
	// Auto Exposure
	// -------------------------------------------------------------------------

	private static CameraSettings applyAutoExposure(MinecraftClient mc, CameraSettings s) {
		if (s.exposureMode() == CameraSettings.EXP_M) return s;

		// The captured framebuffer is ALREADY correctly exposed by the game's own lighting
		// engine (it tone-maps so night is visible), so auto-exposure must NOT re-meter the
		// scene light and re-scale brightness — doing so systematically over-/under-exposes
		// the already-correct render. Instead, centre the meter needle (EV deviation = 0 →
		// F5.6 · 1/60 · ISO 400 reference), exactly like photographica26. mult resolves to 1.
		double targetEV = 0.0;

		return switch (s.exposureMode()) {
			case CameraSettings.EXP_AV -> {
				// Aperture fixed by user; solve for shutter.
				double targetShutter = Math.pow(2.0, targetEV)
						/ (60.0 * sq(5.6 / s.aperture()) * (s.iso() / 400.0));
				int idx = nearestShutterIdx(targetShutter);
				yield idx != s.shutterSpeedIdx() ? s.withShutterIdx(idx) : s;
			}
			case CameraSettings.EXP_TV -> {
				// Shutter fixed by user; solve for aperture.
				double ratio = Math.pow(2.0, targetEV) / (s.shutterSeconds() * 60.0 * (s.iso() / 400.0));
				float ap = (float) (5.6 / Math.sqrt(ratio));
				int idx = nearestApertureIdx(ap);
				float nearest = APERTURE_STOPS.get(idx);
				yield nearest != s.aperture() ? s.withApertureVal(nearest) : s;
			}
			case CameraSettings.EXP_P -> {
				// Program line: keep aperture at F5.6, adjust shutter, clamp to handheld range.
				double targetShutter = Math.pow(2.0, targetEV) / (60.0 * (s.iso() / 400.0));
				int ssIdx = Math.max(5, Math.min(15, nearestShutterIdx(targetShutter)));
				float apNearest = APERTURE_STOPS.get(4); // F5.6
				boolean ssChanged = ssIdx != s.shutterSpeedIdx();
				boolean apChanged = apNearest != s.aperture();
				yield (ssChanged || apChanged) ? s.withApertureAndShutter(apNearest, ssIdx) : s;
			}
			default -> s;
		};
	}

	// -------------------------------------------------------------------------
	// Auto Focus
	// -------------------------------------------------------------------------

	private static CameraSettings applyAutoFocus(MinecraftClient mc, CameraSettings updated, CameraSettings original) {
		int focusMode = original.focusMode();
		if (focusMode == CameraSettings.FOCUS_MF) return updated;

		float targetDepth;
		if (focusMode == CameraSettings.FOCUS_AF) {
			targetDepth = PhotoCapture.lastSceneDepthBlocks;
		} else {
			Float mobDist = nearestMobInCone(mc);
			if (mobDist == null) return updated;
			targetDepth = mobDist;
		}

		float snapped = snapFocus(targetDepth);
		afAtInfinity = (snapped >= CameraSettings.FOCUS_INFINITY);
		// Ease the *current* live focus distance toward the snapped stop in diopter space
		// (focus-pull). This runs every client tick, so the lens racks smoothly over several
		// ticks instead of jumping in one frame.
		float pulled = rackDioptric(original.focusDistance(), snapped);
		// Only short-circuit once we've effectively reached the eased target this
		// tick — comparing against `pulled` (not `snapped`) keeps the easing
		// progressing while the rack is still in motion.
		if (Math.abs(pulled - updated.focusDistance()) < 0.001f) return updated;
		return updated.withFocusDistance(pulled);
	}

	/**
	 * Snaps focus for a tripod (armor-stand mounted) capture, at the moment the shutter fires.
	 * The ordinary AF/MOB tick above only ever runs while the PLAYER is sneaking, since that's
	 * how the viewfinder itself is gated — a camera mounted on a stand is never "sneaked with",
	 * so its AF/MOB modes would otherwise sit frozen at whatever the player's own view last
	 * resolved, unrelated to what the stand is actually pointed at. MF is left untouched, and
	 * this snaps straight to the target rather than racking — there's no live preview on a
	 * tripod shot for a gradual pull to be visible in anyway.
	 */
	public static CameraSettings snapFocusFromArmorStand(MinecraftClient mc,
	                                                      net.minecraft.entity.decoration.ArmorStandEntity stand,
	                                                      CameraSettings s) {
		return snapFocusFromRay(mc, stand.getEyePos(), stand.getRotationVec(1.0f), s);
	}

	/**
	 * Same as {@link #snapFocusFromArmorStand}, but for a mount whose eye/look isn't a fixed
	 * entity to read directly — a piloted drone's "eye" is wherever the render camera already
	 * is (vanilla's rider-camera follow), so the caller passes that instead.
	 */
	/**
	 * AF for a drone-mounted camera. Unlike {@link #snapFocusFromRay}, which in AF mode only
	 * raycasts BLOCKS, this also considers living subjects — including the pilot's own body,
	 * which is visible in every drone shot and is usually what the shot is of. Whichever of the
	 * two is NEARER wins, so a subject standing in front of a wall focuses on the subject
	 * rather than the wall behind them, the way any real AF behaves.
	 *
	 * <p>Not folded into {@code snapFocusFromRay}'s AF branch because a handheld camera's AF
	 * genuinely should ignore the holder (see {@code nearestMobInCone}'s {@code includeSelf}).
	 */
	public static CameraSettings snapFocusFromDroneRay(MinecraftClient mc, Vec3d eye, Vec3d look, CameraSettings s) {
		if (s.focusMode() == CameraSettings.FOCUS_MF || mc.world == null) return s;
		float blockDepth = blockRaycastDepth(mc, eye, look);
		Float subject = nearestMobInCone(mc, eye, look, true);
		float target = (subject != null && subject < blockDepth) ? subject : blockDepth;
		return s.withFocusDistance(snapFocus(target));
	}

	public static CameraSettings snapFocusFromRay(MinecraftClient mc, Vec3d eye, Vec3d look, CameraSettings s) {
		if (s.focusMode() == CameraSettings.FOCUS_MF || mc.world == null) return s;

		float targetDepth;
		if (s.focusMode() == CameraSettings.FOCUS_MOB) {
			Float mobDist = nearestMobInCone(mc, eye, look);
			targetDepth = mobDist != null ? mobDist : CameraSettings.FOCUS_INFINITY;
		} else {
			targetDepth = blockRaycastDepth(mc, eye, look);
		}
		return s.withFocusDistance(snapFocus(targetDepth));
	}

	private static float blockRaycastDepth(MinecraftClient mc, Vec3d eye, Vec3d look) {
		final double maxDist = 1000.0;
		Vec3d end = eye.add(look.multiply(maxDist));
		net.minecraft.util.hit.BlockHitResult hit = mc.world.raycast(
				new net.minecraft.world.RaycastContext(eye, end,
						net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
						net.minecraft.world.RaycastContext.FluidHandling.NONE, mc.player));
		return (hit != null && hit.getType() != net.minecraft.util.hit.HitResult.Type.MISS)
				? (float) eye.distanceTo(hit.getPos()) : CameraSettings.FOCUS_INFINITY;
	}

	/** distance (blocks) -> dioptres (1/distance). Infinity is exactly 0. */
	private static float toDiopters(float distance) {
		return (distance >= CameraSettings.FOCUS_INFINITY) ? 0f : 1f / Math.max(distance, 0.01f);
	}

	/** dioptres -> distance (blocks). Anything within RACK_SNAP_EPS of 0 reads as infinity. */
	private static float fromDiopters(float diopters) {
		return (diopters <= RACK_SNAP_EPS) ? CameraSettings.FOCUS_INFINITY : 1f / diopters;
	}

	/** Eases the current focus distance one tick toward the target stop in diopter space. */
	private static float rackDioptric(float current, float target) {
		float curD = toDiopters(current);
		float tgtD = toDiopters(target);
		float diff = tgtD - curD;
		if (Math.abs(diff) <= RACK_SNAP_EPS) return target;
		float step = diff * RACK_RATE;
		if (step >  RACK_MAX_STEP) step =  RACK_MAX_STEP;
		if (step < -RACK_MAX_STEP) step = -RACK_MAX_STEP;
		return fromDiopters(curD + step);
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	private static float snapFocus(float depth) {
		if (depth >= CameraSettings.FOCUS_INFINITY) return CameraSettings.FOCUS_INFINITY;
		depth = Math.max(0.1f, depth);
		if (depth <= 5.0f) return Math.round(depth * 10f) / 10f;   // 0.1 m steps for macro
		return Math.round(depth);                                    // 1 m steps at range
	}

	private static int nearestApertureIdx(float ap) {
		int best = 0;
		float bestDiff = Float.MAX_VALUE;
		for (int i = 0; i < APERTURE_STOPS.size(); i++) {
			float d = Math.abs(APERTURE_STOPS.get(i) - ap);
			if (d < bestDiff) { bestDiff = d; best = i; }
		}
		return best;
	}

	private static int nearestShutterIdx(double sec) {
		sec = Math.max(1e-6, sec);
		int best = 0;
		double bestDiff = Double.MAX_VALUE;
		for (int i = 0; i < SHUTTER_SECONDS.length; i++) {
			double d = Math.abs(Math.log(SHUTTER_SECONDS[i]) - Math.log(sec));
			if (d < bestDiff) { bestDiff = d; best = i; }
		}
		return best;
	}

	private static Float nearestMobInCone(MinecraftClient mc) {
		if (mc.player == null || mc.world == null) return null;
		// Read from the render camera, not the player's own eye — while drone mode is flying,
		// they're not the same point, and MOB tracking should aim where the shot actually is.
		return nearestMobInCone(mc, RenderCamera.pos(mc), RenderCamera.look(mc));
	}

	private static Float nearestMobInCone(MinecraftClient mc, Vec3d eye, Vec3d look) {
		return nearestMobInCone(mc, eye, look, false);
	}

	/**
	 * @param includeSelf whether the client player counts as a focusable subject. False for a
	 *        handheld camera — you can't photograph yourself through your own viewfinder, and
	 *        letting your own body win the cone test would peg focus at arm's length forever.
	 *        True for a drone-mounted camera, where the pilot's own body is visible in the shot
	 *        (thirdPerson is forced, see DronePilot) and is usually the whole point of the shot.
	 */
	private static Float nearestMobInCone(MinecraftClient mc, Vec3d eye, Vec3d look, boolean includeSelf) {
		if (mc.player == null || mc.world == null) return null;

		double best = Double.MAX_VALUE;
		net.minecraft.util.math.Box searchBox = new net.minecraft.util.math.Box(eye, eye).expand(50.0);
		for (LivingEntity e : mc.world.getEntitiesByClass(LivingEntity.class,
				searchBox, ent -> (includeSelf || ent != mc.player) && ent.isAlive())) {
			//? if >=1.21.11 {
			/*Vec3d toEnt = e.getEntityPos().add(0, e.getHeight() * 0.5, 0).subtract(eye);
			*///?} else {
			Vec3d toEnt = e.getPos().add(0, e.getHeight() * 0.5, 0).subtract(eye);
			//?}
			double dist = toEnt.length();
			if (dist < 0.1) continue;
			if (toEnt.normalize().dotProduct(look) >= MOB_CONE_COS && dist < best) best = dist;
		}
		return best < Double.MAX_VALUE ? (float) best : null;
	}

	private static double sq(double x) { return x * x; }
}
