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

    // cos(5°) — entities must be within this cone of the look direction
    private static final double MOB_CONE_COS = Math.cos(Math.toRadians(5.0));

    // True when AF/MOB resolved its target to the infinity sentinel (sky / no subject).
    // The focus value itself only eases to FAR_ANCHOR (to avoid the foreground-blur
    // flicker that snapping to ∞ caused), so this flag lets the viewfinder still label
    // the distance "inf" instead of showing the far-anchor metres.
    public static volatile boolean afAtInfinity = false;

    // Focus-pull (rack) easing. AF does not snap instantly: focusDistance is eased
    // toward the target in log space, so the lens "pulls" focus like a real motor.
    // Per client tick (20 Hz): move a fraction of the remaining log-distance, capped
    // so a big focus change racks over a visible ~0.6–1.0 s instead of jumping.
    private static final float PULL_RATE     = 0.50f;  // fraction of remaining log-distance / tick
    private static final float PULL_MAX_STEP = 0.22f;  // max log units / tick (caps rack speed)
    private static final float PULL_SNAP_EPS = 0.01f;  // lock onto target below this log-distance
    private static final float FAR_ANCHOR    = 1000.0f; // refocus from ∞ starts here (raycast range)

    /**
     * True when the camera is optically at infinity — either an explicit MF ∞ stop, or AF/MOB
     * having resolved to sky / no subject.
     *
     * <p>The viewfinder label and the DoF shader MUST agree on this, and they used to decide
     * it independently: the HUD consulted {@link #afAtInfinity} and printed "inf", while the
     * blur was handed the raw {@code focusDistance}, which {@link #pullFocus} had clamped to
     * the finite {@link #FAR_ANCHOR}. So in AF mode the shader took its FINITE branch at
     * 1000 blocks and went on blurring the horizon under an "inf" readout.
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
    private static boolean isSeeThrough(net.minecraft.block.Block b) {
        return b instanceof net.minecraft.block.TransparentBlock   // glass, stained, tinted
                || b instanceof net.minecraft.block.PaneBlock;     // panes, iron bars
    }

    /**
     * Raycast for autofocus that does not stop on glass.
     *
     * <p>The plain world raycast reports the pane, because a pane is solid as far as collision
     * is concerned — so aiming the reticle at a window focused on the window. A camera pointed
     * through glass focuses on what is beyond it, so this steps past each see-through block it
     * meets and carries on, up to a few layers, and returns the first thing that is genuinely
     * opaque. (The DoF depth buffer is sampled before the translucent pass for the same
     * reason; this is the CPU-side half of the same idea.)
     *
     * @return the first opaque hit, or the last result if only glass was found
     */
    public static net.minecraft.util.hit.BlockHitResult raycastThroughGlass(
            MinecraftClient mc, Vec3d eye, Vec3d look, double maxDist) {
        Vec3d start = eye;
        Vec3d end   = eye.add(look.multiply(maxDist));
        net.minecraft.util.hit.BlockHitResult hit = null;
        for (int layer = 0; layer < 8; layer++) {
            hit = mc.world.raycast(new net.minecraft.world.RaycastContext(start, end,
                    net.minecraft.world.RaycastContext.ShapeType.OUTLINE,
                    net.minecraft.world.RaycastContext.FluidHandling.NONE, mc.player));
            if (hit == null || hit.getType() == net.minecraft.util.hit.HitResult.Type.MISS) return hit;
            if (!isSeeThrough(mc.world.getBlockState(hit.getBlockPos()).getBlock())) return hit;

            // Walk out of the block we just hit before resuming, otherwise the next cast
            // starts inside it and reports the very same block again.
            net.minecraft.util.math.BlockPos hitPos = hit.getBlockPos();
            Vec3d p = hit.getPos();
            for (int k = 0; k < 40
                    && net.minecraft.util.math.BlockPos.ofFloored(p.x, p.y, p.z).equals(hitPos); k++) {
                p = p.add(look.multiply(0.05));
            }
            start = p.add(look.multiply(0.01));
            if (start.squaredDistanceTo(eye) >= maxDist * maxDist) return hit;
        }
        return hit;
    }

    public static void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return;
        // Track while the sneak viewfinder is up OR while recording (so the baked-in
        // preview blur keeps focus on the subject even when not sneaking).
        boolean active = (SnapmaticaClient.viewfinderSneakEnabled && mc.player.isSneaking())
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
        // When AF has no subject (ray missed / sky), ease toward FAR_ANCHOR rather than
        // snapping to FOCUS_INFINITY. FOCUS_INFINITY as a raw sensor reading means "nothing
        // to focus on" — not "set infinity focus" (that's an explicit MF scroll action).
        // Snapping instantly to ∞ would activate the infinity foreground-blur mode every
        // time the centre pixel sweeps across sky, causing visible flicker and a permanent
        // light bokeh that can't be cleared. Easing to FAR_ANCHOR instead keeps focus
        // smooth; ∞ readout and foreground blur remain available in MF mode.
        if (target >= SnapmaticaClient.FOCUS_INFINITY) target = FAR_ANCHOR;
        // Refocusing back from ∞ (manually set via MF) starts the rack at FAR_ANCHOR.
        if (current >= SnapmaticaClient.FOCUS_INFINITY) current = FAR_ANCHOR;
        current = Math.max(0.01f, current);
        float logCur = (float) Math.log(current);
        float logTar = (float) Math.log(target);
        float diff   = logTar - logCur;
        if (Math.abs(diff) <= PULL_SNAP_EPS) return target;  // lock onto the stop
        float step = diff * PULL_RATE;
        if (step >  PULL_MAX_STEP) step =  PULL_MAX_STEP;
        if (step < -PULL_MAX_STEP) step = -PULL_MAX_STEP;
        return (float) Math.exp(logCur + step);
    }

    /**
     * Snaps a measured scene depth to an AF focus distance.
     *   • depth &gt;= sentinel → infinity (raycast miss / sky / no subject)
     *   • depth &lt;= 5 m      → nearest 0.1 m (macro / close-up precision)
     *   • otherwise          → nearest 1 m, finite (super-telephoto on a 2000 m subject
     *                          focuses at 2000 m, not collapsed to infinity)
     * The 1 m resolution at range matters for super-telephoto, whose depth of field is
     * so shallow that a 10–30 m focus error (the old coarse stops) left distant subjects
     * permanently soft.
     */
    private static float snapFocus(float depth) {
        if (depth >= SnapmaticaClient.FOCUS_INFINITY) return SnapmaticaClient.FOCUS_INFINITY;
        return Math.max(0.1f, depth);
    }

    private static Float nearestMobInCone(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return null;
        Vec3d eye = mc.player.getEyePos();
        Vec3d look = mc.player.getRotationVec(1.0f);

        double best = Double.MAX_VALUE;
        for (LivingEntity e : mc.world.getEntitiesByClass(LivingEntity.class,
                mc.player.getBoundingBox().expand(50.0), ent -> ent != mc.player && ent.isAlive())) {
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
}
