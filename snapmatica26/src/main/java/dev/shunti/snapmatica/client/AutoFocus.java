package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

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

    // True when AF/MOB resolved its target to the infinity sentinel (sky / no subject).
    // The focus value only eases to FAR_ANCHOR to avoid foreground-blur flicker, so
    // this flag lets the viewfinder label the distance "inf" instead of showing metres.
    public static volatile boolean afAtInfinity = false;

    // Focus-pull (rack) easing. AF does not snap instantly: focusDistance is eased
    // toward the target in log space, so the lens "pulls" focus like a real motor.
    private static final float PULL_RATE     = 0.30f;  // fraction of remaining log-distance / tick
    private static final float PULL_MAX_STEP = 0.22f;  // max log units / tick (caps rack speed)
    private static final float PULL_SNAP_EPS = 0.01f;  // lock onto target below this log-distance
    private static final float FAR_ANCHOR    = 1000.0f; // refocus from ∞ starts here (raycast range)
    private static final double MOB_CONE_COS = Math.cos(Math.toRadians(5.0));

    public static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        boolean active = (SnapmaticaClient.viewfinderSneakEnabled && mc.player.isShiftKeyDown())
                || VideoRecorder.isRecording();
        if (!active) return;
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

        float target = snapFocus(targetDepth);
        afAtInfinity = (target >= SnapmaticaClient.FOCUS_INFINITY);
        SnapmaticaClient.focusDistance = pullFocus(SnapmaticaClient.focusDistance, target);
    }

    /** Eases the current focus distance one tick toward the target stop in log space. */
    private static float pullFocus(float current, float target) {
        // Ease toward FAR_ANCHOR instead of snapping to FOCUS_INFINITY on sky hits,
        // to prevent bokeh flicker when the centre pixel briefly sweeps across sky.
        if (target >= SnapmaticaClient.FOCUS_INFINITY) target = FAR_ANCHOR;
        if (current >= SnapmaticaClient.FOCUS_INFINITY) current = FAR_ANCHOR;
        current = Math.max(0.01f, current);
        float logCur = (float) Math.log(current);
        float logTar = (float) Math.log(target);
        float diff   = logTar - logCur;
        if (Math.abs(diff) <= PULL_SNAP_EPS) return target;
        float step = diff * PULL_RATE;
        if (step >  PULL_MAX_STEP) step =  PULL_MAX_STEP;
        if (step < -PULL_MAX_STEP) step = -PULL_MAX_STEP;
        return (float) Math.exp(logCur + step);
    }

    private static float snapFocus(float depth) {
        if (depth >= SnapmaticaClient.FOCUS_INFINITY) return SnapmaticaClient.FOCUS_INFINITY;
        depth = Math.max(0.1f, depth);
        if (depth <= 5.0f) return Math.round(depth * 10f) / 10f;
        return Math.round(depth);
    }

    private static Float nearestMobInCone(Minecraft mc) {
        if (mc.player == null || mc.level == null) return null;
        Vec3 eye  = mc.player.getEyePosition();
        Vec3 look = mc.player.getViewVector(1.0f);

        double best = Double.MAX_VALUE;
        for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class,
                mc.player.getBoundingBox().inflate(50.0),
                ent -> ent != mc.player && ent.isAlive())) {
            Vec3 toEnt = e.position().add(0, e.getBbHeight() * 0.5, 0).subtract(eye);
            double dist = toEnt.length();
            if (dist < 0.1) continue;
            if (toEnt.normalize().dot(look) >= MOB_CONE_COS && dist < best) best = dist;
        }
        return best < Double.MAX_VALUE ? (float) best : null;
    }
}
