package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;

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

    // 999 = infinity-focus sentinel (the blur shader switches to its ∞ branch at >=999),
    // so finite focus is clamped just below it.
    private static final float FOCUS_INFINITY = 999.0f;

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

    /**
     * Snaps a measured scene depth to an AF focus distance.
     *   • depth &gt;= 999      → infinity (raycast miss / sky / beyond detection range)
     *   • depth &lt;= 5 m      → nearest 0.1 m (macro / close-up precision)
     *   • otherwise          → nearest 1 m
     * The 1 m resolution at range matters for super-telephoto, whose depth of field is
     * so shallow that a 10–30 m focus error (the old coarse stops) left distant subjects
     * permanently soft.
     */
    private static float snapFocus(float depth) {
        if (depth >= FOCUS_INFINITY) return FOCUS_INFINITY;
        depth = Math.max(0.1f, depth);
        if (depth <= 5.0f) return Math.round(depth * 10f) / 10f;   // 0.1 m steps
        return Math.min(FOCUS_INFINITY - 1f, Math.round(depth));   // 1 m steps, kept finite
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
