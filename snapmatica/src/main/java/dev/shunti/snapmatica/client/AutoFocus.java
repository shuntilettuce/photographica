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

    /**
     * Whether autofocus most recently RESOLVED to sky / no subject. Records intent only —
     * where the lens actually is comes from {@link #atInfinity()}, and the two differ for as
     * long as the rack takes to travel.
     */
    public static volatile boolean afAtInfinity = false;


    // Manual-focus rack, in dioptres per client tick (20 Hz). RATE is the fraction of the
    // remaining travel covered each tick; MAX caps how fast the barrel can physically turn, so
    // a jump from close focus to infinity takes a visible moment instead of teleporting.
    private static int prevFocusMode = -1;

    private static final float RACK_RATE        = 0.28f;
    private static final float RACK_MAX_DIOPTRE = 0.35f;

    /**
     * True when the camera is optically at infinity — either an explicit MF ∞ stop, or AF/MOB
     * having resolved to sky / no subject.
     *
     * <p>The viewfinder label, the reticle and the DoF shader all have to agree on this, and
     * they once decided it independently — the HUD printing "inf" off the AF intent while the
     * blur worked from a finite distance, so the horizon stayed soft under an "inf" readout.
     */
    public static boolean atInfinity() {
        // Where the LENS is, never where autofocus intends to go. Consulting the intent made
        // the shader jump to the infinity sentinel the instant AF resolved to sky, while the
        // focus itself was still setting off from five metres — so the picture snapped to
        // infinity in one frame however smoothly the rack then travelled. The readout, the
        // reticle and the optics all read the same thing now, which is the image.
        return SnapmaticaClient.focusDistance >= SnapmaticaClient.FOCUS_INFINITY;
    }

    /** Focus distance to hand the DoF shader — the sentinel whenever {@link #atInfinity()}. */
    public static float shaderFocusDistance() {
        return atInfinity() ? SnapmaticaClient.FOCUS_INFINITY : SnapmaticaClient.focusDistance;
    }

    /**
     * Blocks a photographer focuses THROUGH rather than ON: glass of every kind, panes, bars —
     * and barriers, which are invisible in the rendered scene but still solid to collision, so
     * the plain world raycast (and its full-cube outline shape) reports them same as any other
     * block. A camera cannot focus on something it cannot see; treating a barrier as opaque
     * would rack the lens onto empty air with nothing on screen to justify it.
     */
    private static boolean isSeeThrough(net.minecraft.block.Block b) {
        return b instanceof net.minecraft.block.TransparentBlock   // glass, stained, tinted
                || b instanceof net.minecraft.block.PaneBlock      // panes, iron bars
                || b instanceof net.minecraft.block.BarrierBlock;  // invisible collision-only
    }

    /**
     * Raycast for autofocus that does not stop on glass — or on barriers.
     *
     * <p>The plain world raycast reports the pane, because a pane is solid as far as collision
     * is concerned — so aiming the reticle at a window focused on the window. A camera pointed
     * through glass focuses on what is beyond it, so this steps past each see-through block it
     * meets and carries on, up to a few layers, and returns the first thing that is genuinely
     * opaque. (The DoF depth buffer is sampled before the translucent pass for the same
     * reason; this is the CPU-side half of the same idea.) Barriers get the same treatment for
     * a different reason: not translucent, just invisible — never drawn, so never in that depth
     * buffer either, but still solid enough to stop this raycast cold without it.
     *
     * @return the first opaque hit, or the last result if only see-through blocks were found
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
        // An exposure is one instant. Everything the camera was set to when the shutter opened
        // belongs to that instant, and a rack that keeps easing — or an autofocus that
        // re-measures from a viewpoint the burst has moved — would spread the sub-frames across
        // settings that were never used for any single photograph. ApertureIntegration latches
        // the focus it shears against; this is the other half, stopping the value it latched
        // from drifting out from under it while the frames are being taken.
        if (ApertureIntegration.isActive()) return;
        if (mc.player == null || mc.world == null) return;
        // Track while the viewfinder is up (sneaking or freecam) OR while recording (so the
        // baked-in preview blur keeps focus on the subject even when neither is active).
        boolean active = SnapmaticaClient.viewfinderActive(mc) || VideoRecorder.isRecording();
        if (!active) return;

        // The Camera Path menu's focus lock overrides whatever focus mode is selected, but
        // only while a path is actually playing — it exists to hold one subject in focus
        // through a flythrough shot regardless of what MF/AF/MOB would otherwise chase.
        if (Freecam.isPathPlaying()) {
            Float locked = Freecam.pathFocusLockDistance();
            if (locked != null) {
                float target = snapFocus(locked);
                afAtInfinity = false;
                SnapmaticaClient.focusDistance = rackDioptric(SnapmaticaClient.focusDistance, target);
                SnapmaticaClient.focusTarget = SnapmaticaClient.focusDistance;
                return;
            }
        }

        // Handover: autofocus moves the lens without touching the ring, so the moment manual
        // focus takes over, the ring has to be picked up from wherever the lens was left.
        // Otherwise the first manual click would rack back to a stale setting before moving.
        if (SnapmaticaClient.focusMode != prevFocusMode) {
            if (SnapmaticaClient.focusMode == FOCUS_MF) {
                SnapmaticaClient.focusTarget = SnapmaticaClient.focusDistance;
            }
            if (prevFocusMode == FOCUS_MOB && SnapmaticaClient.focusMode != FOCUS_MOB) {
                lastMobFocus = null;
            }
            prevFocusMode = SnapmaticaClient.focusMode;
        }

        // Manual focus racks too. The ring sets the destination; the lens travels there.
        if (SnapmaticaClient.focusMode == FOCUS_MF) {
            SnapmaticaClient.focusDistance =
                    rackDioptric(SnapmaticaClient.focusDistance, SnapmaticaClient.focusTarget);
            return;
        }

        float targetDepth;
        if (SnapmaticaClient.focusMode == FOCUS_AF) {
            targetDepth = PhotoCapture.lastSceneDepthBlocks;
        } else if (SnapmaticaClient.focusMode == FOCUS_MOB) {
            Float mobDist = nearestMobInCone(mc);
            if (mobDist == null) mobDist = trackedMobDistance(mc);
            if (mobDist == null) return;
            targetDepth = mobDist;
        } else {
            return;
        }

        float target = snapFocus(targetDepth);
        afAtInfinity = (target >= SnapmaticaClient.FOCUS_INFINITY);
        // Same dioptric rack manual focus uses, and aimed at the REAL target including the far
        // stop. It used to ease toward a finite FAR_ANCHOR instead, on the grounds that
        // snapping to infinity flickered whenever the centre pixel swept across sky — but that
        // was a property of snapping, not of arriving. A rack with a time constant damps the
        // sweep on its own, and stopping short of the stop meant AF could never actually reach
        // infinity: the far field stayed partly blurred with the camera pointed at the sky.
        SnapmaticaClient.focusDistance = rackDioptric(SnapmaticaClient.focusDistance, target);
        // Keep the ring in step, so switching to manual does not rack back to a stale setting.
        SnapmaticaClient.focusTarget = SnapmaticaClient.focusDistance;
    }

    /** Distance to refractive power. Infinity is simply zero, which is why this space works. */
    private static float toDiopters(float d) {
        return (d >= SnapmaticaClient.FOCUS_INFINITY) ? 0f : 1f / Math.max(d, 0.01f);
    }

    private static float fromDiopters(float dio) {
        // Below this the remaining travel is a few metres out of infinity; call it arrived,
        // so the readout and the shader's infinity branch actually engage.
        return (dio <= 1f / SnapmaticaClient.FOCUS_INFINITY * 10f) ? SnapmaticaClient.FOCUS_INFINITY
                                                                   : 1f / dio;
    }

    /**
     * Moves the focus one tick toward its target, interpolating in DIOPTRES rather than in
     * distance or log-distance.
     *
     * <p>Distance runs to infinity and log-distance runs to negative infinity, so neither can
     * represent the far stop — which is why the old manual path just assigned it and the image
     * snapped. Refractive power puts infinity at exactly 0, a finite value the rack can travel
     * to like any other, and it is roughly how a helicoid moves anyway: the far half of the
     * scale is a sliver of the ring's travel.
     */
    private static float rackDioptric(float current, float target) {
        float cur = toDiopters(current);
        float tar = toDiopters(target);
        float diff = tar - cur;
        // One part in a hundred thousand of a dioptre — the refractive power of the infinity
        // sentinel itself, so "arrived" means arrived at the far stop and nothing sooner. At
        // the previous 1e-4 this equalled the power of FOCUS_MAX exactly, so the final step of
        // a rack out to infinity always completed in a single tick: the one step anyone would
        // notice. Tighter than this only adds an invisible tail and delays the readout.
        if (Math.abs(diff) <= 1e-5f) return target;
        float step = diff * RACK_RATE;
        float ceil = RACK_MAX_DIOPTRE;
        if (step >  ceil) step =  ceil;
        if (step < -ceil) step = -ceil;
        return fromDiopters(cur + step);
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

    /** The entity MOB-mode autofocus most recently locked onto — kept warm so a subject that
     *  drifts a few degrees out of the narrow centre cone (or steps fully out of frame for a
     *  moment) stays the thing in focus instead of the rack freezing wherever it last measured.
     *  Cleared once the entity dies, unloads, or MOB mode is left for another focus mode. The
     *  player counts like any other candidate here — freecam already lets it be an ordinary
     *  MOB-mode subject (see the search below), and once it's the one locked on it should stay
     *  locked on the same way anything else would. */
    private static LivingEntity lastMobFocus = null;

    private static Float nearestMobInCone(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) return null;
        Vec3d eye = SnapmaticaClient.cameraPos(mc);
        Vec3d look = SnapmaticaClient.cameraLook(mc);

        LivingEntity bestEntity = null;
        double best = Double.MAX_VALUE;
        // Rooted at the camera eye rather than the player's own bounding box, so this still
        // covers the cone when freecam has moved the camera away from the player's body.
        net.minecraft.util.math.Box searchBox = new net.minecraft.util.math.Box(eye, eye).expand(50.0);
        // The player themself is a valid target once freecam has moved the camera away from
        // them — a selfie is exactly a subject in front of a lens the player isn't holding.
        for (LivingEntity e : mc.world.getEntitiesByClass(LivingEntity.class,
                searchBox, ent -> (ent != mc.player || Freecam.isActive()) && ent.isAlive())) {
            //? if >=1.21.10 {
            Vec3d toEnt = e.getEntityPos().add(0, e.getHeight() * 0.5, 0).subtract(eye);
            //?} else {
            /*Vec3d toEnt = e.getPos().add(0, e.getHeight() * 0.5, 0).subtract(eye);
            *///?}
            double dist = toEnt.length();
            if (dist < 0.1) continue;
            if (toEnt.normalize().dotProduct(look) >= MOB_CONE_COS && dist < best) {
                best = dist;
                bestEntity = e;
            }
        }
        if (bestEntity != null) lastMobFocus = bestEntity;
        return best < Double.MAX_VALUE ? (float) best : null;
    }

    /** Distance to whatever {@link #lastMobFocus} caught, if it's still around to measure —
     *  {@link #nearestMobInCone}'s fallback once nothing sits in the narrow cone right now, so
     *  a subject that only stepped a little off-centre keeps its focus instead of MOB mode
     *  losing the thread on every stray look. Not itself cone-limited: the whole point is to
     *  keep tracking a subject the cone no longer sees. */
    private static Float trackedMobDistance(MinecraftClient mc) {
        LivingEntity e = lastMobFocus;
        if (e == null || !e.isAlive() || e.isRemoved()) {
            lastMobFocus = null;
            return null;
        }
        Vec3d eye = SnapmaticaClient.cameraPos(mc);
        //? if >=1.21.10 {
        Vec3d toEnt = e.getEntityPos().add(0, e.getHeight() * 0.5, 0).subtract(eye);
        //?} else {
        /*Vec3d toEnt = e.getPos().add(0, e.getHeight() * 0.5, 0).subtract(eye);
        *///?}
        return (float) toEnt.length();
    }
}
