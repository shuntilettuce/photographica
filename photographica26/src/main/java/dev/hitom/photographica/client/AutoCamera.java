package dev.hitom.photographica.client;

import dev.hitom.photographica.component.CameraSettings;
import dev.hitom.photographica.item.CameraItem;
import dev.hitom.photographica.item.FilmCameraItem;
import dev.hitom.photographica.network.UpdateCameraSettingsPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

import java.util.List;

/**
 * Client-side auto-exposure and auto-focus tick handler.
 * Called each client tick while the player is sneaking with a camera (viewfinder active).
 */
@Environment(EnvType.CLIENT)
public final class AutoCamera {
	private AutoCamera() {}

	private static final List<Float> FOCUS_STOPS = List.of(
			0.3f, 0.5f, 0.7f, 1.0f, 1.2f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f,
			5.0f, 6.0f, 7.0f, 8.0f, 10.0f, 12.0f, 15.0f, 20.0f, 25.0f, 30.0f,
			40.0f, 50.0f, 70.0f, 100.0f, 999.0f);
	private static final List<Float> APERTURE_STOPS = List.of(
			1.4f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f, 11.0f, 16.0f, 22.0f);
	private static final double[] SHUTTER_SECONDS = {
			30.0, 15.0, 8.0, 4.0, 2.0, 1.0,
			0.5, 0.25, 0.125, 1.0 / 15, 1.0 / 30, 1.0 / 60,
			1.0 / 125, 1.0 / 250, 1.0 / 500, 1.0 / 1000, 1.0 / 2000, 1.0 / 4000
	};

	// cos(5°) — entities must be within this cone of the look direction
	private static final double MOB_CONE_COS = Math.cos(Math.toRadians(5.0));

	public static void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null) return;
		if (!mc.player.isShiftKeyDown()) return;

		ItemStack stack = mc.player.getMainHandItem();
		boolean isFilm = false;
		if (stack.getItem() instanceof FilmCameraItem) {
			isFilm = true;
		} else if (!(stack.getItem() instanceof CameraItem)) {
			stack = mc.player.getOffhandItem();
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

	private static CameraSettings applyAutoExposure(Minecraft mc, CameraSettings s) {
		if (s.exposureMode() == CameraSettings.EXP_M) return s;

		// Map world light level (0-15) to a target EV deviation.
		int light = mc.level.getLightEmission(mc.player.blockPosition());
		double targetEV = -(light - 10) * 0.7;

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

	private static CameraSettings applyAutoFocus(Minecraft mc, CameraSettings updated, CameraSettings original) {
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
		if (Math.abs(snapped - updated.focusDistance()) < 0.001f) return updated;
		return updated.withFocusDistance(snapped);
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	public static float snapFocus(float depth) {
		depth = Math.max(0.01f, depth);
		float logDepth = (float) Math.log(depth);
		float best = FOCUS_STOPS.get(0);
		float bestDiff = Float.MAX_VALUE;
		for (float stop : FOCUS_STOPS) {
			float d = Math.abs(logDepth - (float) Math.log(stop));
			if (d < bestDiff) { bestDiff = d; best = stop; }
		}
		return best;
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

	private static Float nearestMobInCone(Minecraft mc) {
		if (mc.player == null || mc.level == null) return null;
		Vec3 eye = mc.player.getEyePosition(1.0f);
		Vec3 look = mc.player.getViewVector(1.0f);

		double best = Double.MAX_VALUE;
		for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class,
				mc.player.getBoundingBox().inflate(50.0), ent -> ent != mc.player && ent.isAlive())) {
			Vec3 toEnt = e.position().add(0, e.getBbHeight() * 0.5, 0).subtract(eye);
			double dist = toEnt.length();
			if (dist < 0.1) continue;
			if (toEnt.normalize().dot(look) >= MOB_CONE_COS && dist < best) best = dist;
		}
		return best < Double.MAX_VALUE ? (float) best : null;
	}

	// -------------------------------------------------------------------------
	// Armor stand AF
	// -------------------------------------------------------------------------

	/**
	 * Computes and returns a focus-stop-snapped distance for the given armor stand.
	 */
	public static float snapFocusFromArmorStand(ArmorStand stand, ClientLevel world) {
		Vec3 eye  = stand.getEyePosition(1.0f);
		Vec3 look = stand.getViewVector(1.0f);

		// Block raycast (64-block max)
		HitResult hit = world.clip(new ClipContext(
				eye, eye.add(look.scale(64.0)),
				ClipContext.Block.OUTLINE,
				ClipContext.Fluid.NONE,
				stand));
		float depth = (float) hit.getLocation().distanceTo(eye);

		// Entity scan: find nearest living entity within the 5° forward cone
		for (LivingEntity e : world.getEntitiesOfClass(
				LivingEntity.class,
				stand.getBoundingBox().inflate(50.0),
				ent -> ent.isAlive())) {
			Vec3 toEnt = e.position().add(0, e.getBbHeight() * 0.5, 0).subtract(eye);
			double dist = toEnt.length();
			if (dist < 0.1) continue;
			if (toEnt.normalize().dot(look) >= MOB_CONE_COS) {
				depth = Math.min(depth, (float) dist);
			}
		}

		return snapFocus(depth);
	}

	private static double sq(double x) { return x * x; }
}
