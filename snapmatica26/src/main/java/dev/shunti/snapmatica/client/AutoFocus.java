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
    private static final float PULL_RATE     = 0.50f;  // fraction of remaining log-distance / tick
    private static final float PULL_MAX_STEP = 0.22f;  // max log units / tick (caps rack speed)
    private static final float PULL_SNAP_EPS = 0.01f;  // lock onto target below this log-distance
    private static final float FAR_ANCHOR    = 1000.0f; // refocus from ∞ starts here (raycast range)
    private static final double MOB_CONE_COS = Math.cos(Math.toRadians(5.0));

    /**
     * True when the camera is optically at infinity — either an explicit MF ∞ stop, or AF/MOB
     * having resolved to sky / no subject.
     *
     * <p>The viewfinder label, the focus reticle and the DoF shader MUST agree on this, and
     * they used to decide it independently: the HUD consulted {@link #afAtInfinity} and printed
     * "inf", while the blur was handed the raw {@code focusDistance}, which {@link #pullFocus}
     * had clamped to the finite {@link #FAR_ANCHOR}. So in AF mode the shader took its FINITE
     * branch at 1000 blocks and went on blurring the horizon under an "inf" readout, and the
     * reticle — testing focusDistance directly — never matched, fell through to the scene-depth
     * test, and went red on a correctly focused sky.
     */
    public static boolean atInfinity() {
        return SnapmaticaClient.focusDistance >= SnapmaticaClient.FOCUS_INFINITY
                || (SnapmaticaClient.focusMode != FOCUS_MF && afAtInfinity);
    }

    /** Focus distance to hand the DoF shader — the sentinel whenever {@link #atInfinity()}. */
    public static float shaderFocusDistance() {
        return atInfinity() ? SnapmaticaClient.FOCUS_INFINITY : SnapmaticaClient.focusDistance;
    }

    /** Blocks a photographer focuses THROUGH rather than ON: glass of every kind, panes, bars. */
    private static boolean isSeeThrough(net.minecraft.world.level.block.Block b) {
        return b instanceof net.minecraft.world.level.block.TransparentBlock  // glass, stained, tinted
                || b instanceof net.minecraft.world.level.block.IronBarsBlock; // panes, iron bars
    }

    /**
     * Raycast for autofocus that does not stop on glass.
     *
     * <p>The plain clip reports the pane, because a pane is solid as far as collision is
     * concerned — so aiming the reticle at a window focused on the window. A camera pointed
     * through glass focuses on what is beyond it, so this steps past each see-through block it
     * meets and carries on, up to a few layers, returning the first genuinely opaque hit. (The
     * DoF depth buffer is sampled before the translucent pass for the same reason; this is the
     * CPU-side half of the same idea.)
     */
    public static net.minecraft.world.phys.BlockHitResult raycastThroughGlass(
            Minecraft mc, Vec3 eye, Vec3 look, double maxDist) {
        Vec3 start = eye;
        Vec3 end   = eye.add(look.scale(maxDist));
        net.minecraft.world.phys.BlockHitResult hit = null;
        for (int layer = 0; layer < 8; layer++) {
            hit = mc.level.clip(new net.minecraft.world.level.ClipContext(start, end,
                    net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
            if (hit == null || hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) return hit;
            if (!isSeeThrough(mc.level.getBlockState(hit.getBlockPos()).getBlock())) return hit;

            // Walk out of the block just hit before resuming, otherwise the next cast starts
            // inside it and reports the very same block again.
            net.minecraft.core.BlockPos hitPos = hit.getBlockPos();
            Vec3 p = hit.getLocation();
            for (int k = 0; k < 40
                    && net.minecraft.core.BlockPos.containing(p.x, p.y, p.z).equals(hitPos); k++) {
                p = p.add(look.scale(0.05));
            }
            start = p.add(look.scale(0.01));
            if (start.distanceToSqr(eye) >= maxDist * maxDist) return hit;
        }
        return hit;
    }

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
        return Math.max(0.1f, depth);
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
