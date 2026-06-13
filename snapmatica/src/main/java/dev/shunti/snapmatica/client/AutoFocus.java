package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Client-side auto-focus tick handler. Runs while the sneak viewfinder is active.
 *
 * Focus modes:
 *   MF  – manual; focusDistance left untouched (scroll-wheel control only)
 *   AF  – snap focusDistance to the centre scene depth (PhotoCapture.lastSceneDepthBlocks)
 *   MOB – snap focusDistance to the nearest living entity in a 5° forward cone
 */
@Environment(EnvType.CLIENT)
public final class AutoFocus {
    private AutoFocus() {}

    private static final int FOCUS_MF  = 0;
    private static final int FOCUS_AF  = 1;
    private static final int FOCUS_MOB = 2;

    // AF-only stops. Dense 0.1 m increments out to 5 m give precise macro / close-up
    // focusing (where small distance errors visibly defocus the subject), then coarser
    // photographic stops out to 999 (= infinity focus, subjects beyond ~940 m).
    private static final List<Float> FOCUS_STOPS = List.of(
            0.1f,  0.2f,  0.3f,  0.4f,  0.5f,  0.6f,  0.7f,  0.8f,  0.9f,  1.0f,
            1.1f,  1.2f,  1.3f,  1.4f,  1.5f,  1.6f,  1.7f,  1.8f,  1.9f,  2.0f,
            2.1f,  2.2f,  2.3f,  2.4f,  2.5f,  2.6f,  2.7f,  2.8f,  2.9f,  3.0f,
            3.1f,  3.2f,  3.3f,  3.4f,  3.5f,  3.6f,  3.7f,  3.8f,  3.9f,  4.0f,
            4.1f,  4.2f,  4.3f,  4.4f,  4.5f,  4.6f,  4.7f,  4.8f,  4.9f,  5.0f,
            6.0f,  7.0f,  8.0f,  10.0f, 12.0f, 14.0f, 17.0f, 20.0f, 24.0f, 29.0f,
            35.0f, 42.0f, 50.0f, 60.0f, 73.0f, 87.0f, 105.0f, 125.0f, 150.0f, 180.0f,
            215.0f, 260.0f, 310.0f, 375.0f, 450.0f, 540.0f, 650.0f, 780.0f, 940.0f, 999.0f);

    // cos(5°) — entities must be within this cone of the look direction
    private static final double MOB_CONE_COS = Math.cos(Math.toRadians(5.0));

    // Focus-pull (rack) easing. AF does not snap instantly: focusDistance is eased
    // toward the target in log space, so the lens "pulls" focus like a real motor.
    // Per client tick (20 Hz): move a fraction of the remaining log-distance, capped
    // so a big focus change racks over a visible ~0.6–1.0 s instead of jumping.
    private static final float PULL_RATE     = 0.30f;  // fraction of remaining log-distance / tick
    private static final float PULL_MAX_STEP = 0.22f;  // max log units / tick (caps rack speed)
    private static final float PULL_SNAP_EPS = 0.01f;  // lock onto target below this log-distance

    public static void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return;
        if (!SnapmaticaClient.viewfinderSneakEnabled || !mc.player.isSneaking()) return;
        if (SnapmaticaClient.focusMode == FOCUS_MF) return;

        float targetDepth;
        if (SnapmaticaClient.focusMode == FOCUS_AF) {
            targetDepth = PhotoCapture.lastSceneDepthBlocks;
        } else if (SnapmaticaClient.focusMode == FOCUS_MOB) {
            Float mobDist = nearestMobInCone(mc);
            if (mobDist == null) return;
            targetDepth = mobDist;
        } else {
            return;
        }

        SnapmaticaClient.focusDistance = pullFocus(SnapmaticaClient.focusDistance, snapFocus(targetDepth));
    }

    /** Eases the current focus distance one tick toward the target stop in log space. */
    private static float pullFocus(float current, float target) {
        current = Math.max(0.01f, current);
        float logCur = (float) Math.log(current);
        float logTar = (float) Math.log(target);
        float diff   = logTar - logCur;
        if (Math.abs(diff) <= PULL_SNAP_EPS) return target;  // lock (keeps exact 999 = ∞)
        float step = diff * PULL_RATE;
        if (step >  PULL_MAX_STEP) step =  PULL_MAX_STEP;
        if (step < -PULL_MAX_STEP) step = -PULL_MAX_STEP;
        return (float) Math.exp(logCur + step);
    }

    private static float snapFocus(float depth) {
        depth = Math.max(0.01f, depth);
        // Snap in log space so focus can reach distant stops (up to 999 = infinity)
        // without the linear gap between 100 and 999 swallowing everything.
        float logDepth = (float) Math.log(depth);
        float best = FOCUS_STOPS.get(0);
        float bestDiff = Float.MAX_VALUE;
        for (float stop : FOCUS_STOPS) {
            float d = Math.abs(logDepth - (float) Math.log(stop));
            if (d < bestDiff) { bestDiff = d; best = stop; }
        }
        return best;
    }

    private static Float nearestMobInCone(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return null;
        Vec3d eye = mc.player.getEyePos();
        Vec3d look = mc.player.getRotationVec(1.0f);

        double best = Double.MAX_VALUE;
        for (LivingEntity e : mc.world.getEntitiesByClass(LivingEntity.class,
                mc.player.getBoundingBox().expand(50.0), ent -> ent != mc.player && ent.isAlive())) {
            //? if >=1.21.11 {
            /*Vec3d toEnt = e.getEntityPos().add(0, e.getHeight() * 0.5, 0).subtract(eye);*/
            //?} else {
            Vec3d toEnt = e.getPos().add(0, e.getHeight() * 0.5, 0).subtract(eye);
            //?}
            double dist = toEnt.length();
            if (dist < 0.1) continue;
            if (toEnt.normalize().dotProduct(look) >= MOB_CONE_COS && dist < best) best = dist;
        }
        return best < Double.MAX_VALUE ? (float) best : null;
    }
}
