package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Draws the recorded camera path: a smooth curve through every keyframe's position, and a
 * camera frustum (a truncated pyramid, near plane to far plane) at each one, so the shot is
 * visible while it's being built rather than only once Play is pressed.
 *
 * <p>Drawn as a 2D overlay projected onto the HUD, not as real 3D world geometry. The first
 * version used real world-space line geometry, which worked fine under the vanilla renderer
 * but read as faint and washed out under Photon (and presumably any other Iris shaderpack) —
 * shaderpacks intercept and re-shade Minecraft's own line render layer as part of their normal
 * world lighting/tone-mapping pass, and a bright, deliberately-flat debug colour is exactly
 * what that pass is built to darken and desaturate. The HUD, unlike the world, renders after
 * the shaderpack's post-processing composite — the same reason text, the crosshair and every
 * other HUD element look the same with or without a shaderpack — so projecting the path into
 * 2D and drawing it there sidesteps the problem entirely rather than fighting it. The trade-off
 * is that this no longer occludes behind terrain the way real world geometry would; for a
 * planning aid you're actively composing a shot with, always-visible is the more useful default
 * anyway.
 *
 * <p>The view and projection are computed fresh every frame from {@code Camera.rotation()} and
 * the same focal-length FOV formula {@code GameRendererMixin} uses, rather than reading
 * Mojang's own {@code CameraRenderState.projectionMatrix} — that matrix is only valid for the
 * frame {@code GameRenderer.extractCamera} built it on, and this HUD element runs from a
 * separately-registered {@code HudElementRegistry} callback whose exact ordering relative to
 * that isn't a contract worth depending on. Building both matrices here directly needs no
 * shared state and can never observe anything other than the real camera right now.
 */
@Environment(EnvType.CLIENT)
public final class CameraPathRenderer {
    private CameraPathRenderer() {}

    private static final int CURVE_SEGMENTS_PER_SPAN = 12;
    private static final double FRUSTUM_NEAR = 0.15, FRUSTUM_FAR = 0.55;
    // Same 36x24 mm sensor convention as GameRendererMixin's frameFov, always landscape here —
    // a gizmo does not need to track whichever orientation was active when the keyframe was
    // recorded, just give an honest sense of the angle of view.
    private static final double SENSOR_HALF_W = 18.0, SENSOR_HALF_H = 12.0;

    private static final int CURVE_COLOR = 0xFF64EBFF;
    private static final int CURVE_BLOCKED_COLOR = 0xFFFFA000;
    private static final int FRUSTUM_COLOR = 0xFFFF5A46;
    private static final int LINE_PX = 3;

    public static void render(net.minecraft.client.gui.GuiGraphicsExtractor ctx,
                              net.minecraft.client.DeltaTracker tickCounter) {
        if (!Freecam.isActive() || Freecam.isPathPlaying()) return;
        List<Freecam.Keyframe> path = Freecam.getPath();
        if (path.isEmpty()) return;

        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.screen != null) return;

        net.minecraft.client.Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.position();
        int sw = ctx.guiWidth(), sh = ctx.guiHeight();

        // View: the camera's own live rotation, conjugated — the same transform Minecraft's
        // own renderer builds its modelview matrix from (Camera.rotation() is "camera
        // orientation in world space"; a world→view transform needs its inverse, and a unit
        // quaternion's inverse is its conjugate).
        org.joml.Quaternionf view = camera.rotation().conjugate(new org.joml.Quaternionf());

        // Projection: the exact FOV GameRendererMixin puts on screen right now, so this can
        // never drift out of step with it — focal-length-derived while a lens is on (matching
        // GameRendererMixin's own gating), the plain vanilla FOV setting otherwise.
        double aspect = (double) mc.getWindow().getWidth()
                / Math.max(1, mc.getWindow().getHeight());
        double fovDeg;
        if (VideoRecorder.isRecording() || SnapmaticaClient.lensType != 0) {
            float focal = Freecam.currentFocalLengthMm(tickCounter.getGameTimeDeltaPartialTick(true));
            fovDeg = focal > 0 ? frameFovDegrees(focal, aspect) : mc.options.fov().get();
        } else {
            fovDeg = mc.options.fov().get();
        }
        org.joml.Matrix4f proj = new org.joml.Matrix4f().perspective(
                (float) Math.toRadians(fovDeg), (float) aspect, 0.05f, 1000f);

        if (path.size() >= 2) {
            for (int i = 0; i < path.size() - 1; i++) {
                Freecam.Keyframe p0 = path.get(Math.max(0, i - 1));
                Freecam.Keyframe p1 = path.get(i);
                Freecam.Keyframe p2 = path.get(i + 1);
                Freecam.Keyframe p3 = path.get(Math.min(path.size() - 1, i + 2));
                Vec3 prev = p1.pos();
                for (int s = 1; s <= CURVE_SEGMENTS_PER_SPAN; s++) {
                    double t = (double) s / CURVE_SEGMENTS_PER_SPAN;
                    Vec3 cur = catmullRom(p0.pos(), p1.pos(), p2.pos(), p3.pos(), t);
                    boolean blocked = mc.level != null && segmentBlocked(mc, prev, cur);
                    drawWorldSegment(ctx, camPos, view, proj, sw, sh, prev, cur, blocked ? CURVE_BLOCKED_COLOR : CURVE_COLOR);
                    prev = cur;
                }
            }
        }

        for (int i = 0; i < path.size(); i++) {
            Freecam.Keyframe kf = path.get(i);
            drawFrustum(ctx, camPos, view, proj, sw, sh, kf);
            drawIndex(ctx, mc, camPos, view, proj, sw, sh, kf.pos(), i + 1);
        }
    }

    /** The order along the path is otherwise invisible once more than a couple of keyframes
     *  are down — a plain number floating above each one, same convention as counting from 1
     *  the rest of the mod's own messages already use. */
    private static void drawIndex(net.minecraft.client.gui.GuiGraphicsExtractor ctx, net.minecraft.client.Minecraft mc,
                                  Vec3 camPos, org.joml.Quaternionf view, org.joml.Matrix4f proj,
                                  int sw, int sh, Vec3 pos, int number) {
        double[] p = worldToScreen(pos, camPos, view, proj, sw, sh);
        if (p == null) return;
        String label = Integer.toString(number);
        ctx.centeredText(mc.font, label,
                (int) Math.round(p[0]), (int) Math.round(p[1]) - 14, 0xFFFFFFFF);
    }

    private static void drawFrustum(net.minecraft.client.gui.GuiGraphicsExtractor ctx, Vec3 camPos,
                                    org.joml.Quaternionf view, org.joml.Matrix4f proj, int sw, int sh,
                                    Freecam.Keyframe kf) {
        double yawRad = Math.toRadians(kf.yaw());
        double pitchRad = Math.toRadians(kf.pitch());
        // Same forward/right convention as Freecam's own flight math.
        Vec3 forward = new Vec3(
                -Math.sin(yawRad) * Math.cos(pitchRad),
                -Math.sin(pitchRad),
                Math.cos(yawRad) * Math.cos(pitchRad));
        Vec3 right = new Vec3(-Math.cos(yawRad), 0, -Math.sin(yawRad)).normalize();
        Vec3 up = right.cross(forward).normalize();

        double focal = Math.max(1, kf.focalLengthMm());
        double halfAngleH = Math.atan(SENSOR_HALF_W / focal);
        double halfAngleV = Math.atan(SENSOR_HALF_H / focal);

        Vec3[] near = rect(kf.pos(), forward, right, up, FRUSTUM_NEAR,
                Math.tan(halfAngleH) * FRUSTUM_NEAR, Math.tan(halfAngleV) * FRUSTUM_NEAR);
        Vec3[] far = rect(kf.pos(), forward, right, up, FRUSTUM_FAR,
                Math.tan(halfAngleH) * FRUSTUM_FAR, Math.tan(halfAngleV) * FRUSTUM_FAR);

        for (int i = 0; i < 4; i++) {
            drawWorldSegment(ctx, camPos, view, proj, sw, sh, near[i], near[(i + 1) % 4], FRUSTUM_COLOR);
            drawWorldSegment(ctx, camPos, view, proj, sw, sh, far[i], far[(i + 1) % 4], FRUSTUM_COLOR);
            drawWorldSegment(ctx, camPos, view, proj, sw, sh, near[i], far[i], FRUSTUM_COLOR);
        }
    }

    private static Vec3[] rect(Vec3 origin, Vec3 forward, Vec3 right, Vec3 up,
                                double dist, double halfW, double halfH) {
        Vec3 center = origin.add(forward.scale(dist));
        return new Vec3[]{
                center.add(right.scale(-halfW)).add(up.scale(halfH)),
                center.add(right.scale(halfW)).add(up.scale(halfH)),
                center.add(right.scale(halfW)).add(up.scale(-halfH)),
                center.add(right.scale(-halfW)).add(up.scale(-halfH)),
        };
    }

    /** Projects a world-space segment to screen space and stamps it; silently drops either end
     *  that falls behind the camera rather than drawing the wrapped-around garbage a naive
     *  perspective divide would produce there. */
    private static void drawWorldSegment(net.minecraft.client.gui.GuiGraphicsExtractor ctx, Vec3 camPos,
                                         org.joml.Quaternionf view, org.joml.Matrix4f proj, int sw, int sh,
                                         Vec3 a, Vec3 b, int color) {
        double[] pa = worldToScreen(a, camPos, view, proj, sw, sh);
        double[] pb = worldToScreen(b, camPos, view, proj, sw, sh);
        if (pa == null || pb == null) return;
        stampLine(ctx, pa[0], pa[1], pb[0], pb[1], color);
    }

    /** Whether a solid block sits between two points — the curve draws itself in a warning
     *  colour where it does, since a real flight along it would clip straight through. Tests
     *  against the actual collision shape (not the glass-passthrough AutoFocus uses for
     *  focusing) — a drone would bump a window even if a photographer can see through it. */
    private static boolean segmentBlocked(net.minecraft.client.Minecraft mc, Vec3 a, Vec3 b) {
        net.minecraft.world.phys.BlockHitResult hit = mc.level.clip(new net.minecraft.world.level.ClipContext(
                a, b, net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
        return hit != null && hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS;
    }

    /**
     * Vertical FOV that makes the frame span the focal length's true field — the same formula
     * (and the same reasoning: anchor on whichever edge, height or width, actually constrains
     * the 3:2 frame against the window) as {@code GameRendererMixin.snapmatica$frameFov}.
     * Duplicated rather than shared because the two call sites need it in different forms and
     * this is short enough that the duplication is cheaper to keep in sync than a shared
     * indirection would be to wire up.
     */
    private static double frameFovDegrees(double focalMm, double windowAspect) {
        boolean portrait = SnapmaticaClient.portraitOrientation;
        double halfH = portrait ? 18.0 : 12.0;
        double halfW = portrait ? 12.0 : 18.0;
        double frameAspect = halfW / halfH;
        double vHalfMm = (windowAspect >= frameAspect) ? halfH : halfW / windowAspect;
        return Math.toDegrees(2.0 * Math.atan(vHalfMm / focalMm));
    }

    /** World point → screen pixel: relative-to-camera position, rotated into view space by the
     *  camera's own live orientation, then through the projection and a perspective divide.
     *  Returns null for a point behind the camera (w <= 0) instead of the mirrored garbage a
     *  straight divide would give there. */
    private static double[] worldToScreen(Vec3 pos, Vec3 camPos, org.joml.Quaternionf view,
                                          org.joml.Matrix4f proj, int sw, int sh) {
        org.joml.Vector3f rel = new org.joml.Vector3f(
                (float) (pos.x - camPos.x), (float) (pos.y - camPos.y), (float) (pos.z - camPos.z));
        view.transform(rel);
        org.joml.Vector4f v = new org.joml.Vector4f(rel, 1f);
        proj.transform(v);
        if (v.w <= 0.0001f) return null;
        double ndcX = v.x / v.w, ndcY = v.y / v.w;
        double screenX = (ndcX * 0.5 + 0.5) * sw;
        double screenY = (1.0 - (ndcY * 0.5 + 0.5)) * sh;
        return new double[]{screenX, screenY};
    }

    /** GuiGraphicsExtractor has no arbitrary-angle line primitive, only axis-aligned fills —
     *  this spins its 2D matrix stack to the segment's own angle and fills one straight
     *  rectangle in that rotated space, rather than walking the segment and stamping many
     *  small squares along it. One fill() instead of dozens per segment, and a clean straight
     *  edge instead of a visibly chunky dotted one. */
    private static void stampLine(net.minecraft.client.gui.GuiGraphicsExtractor ctx,
                                  double x0, double y0, double x1, double y1, int color) {
        double dx = x1 - x0, dy = y1 - y0;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 0.01) return;
        float angle = (float) Math.atan2(dy, dx);
        int half = LINE_PX / 2;
        int halfLen = (int) Math.round(len / 2.0);

        org.joml.Matrix3x2fStack m = ctx.pose();
        m.pushMatrix();
        m.translate((float) ((x0 + x1) / 2.0), (float) ((y0 + y1) / 2.0));
        m.rotate(angle);
        ctx.fill(-halfLen, -half, halfLen, -half + LINE_PX, color);
        m.popMatrix();
    }

    private static Vec3 catmullRom(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return new Vec3(
                cr(p0.x, p1.x, p2.x, p3.x, t, t2, t3),
                cr(p0.y, p1.y, p2.y, p3.y, t, t2, t3),
                cr(p0.z, p1.z, p2.z, p3.z, t, t2, t3));
    }

    private static double cr(double p0, double p1, double p2, double p3, double t, double t2, double t3) {
        return 0.5 * ((2 * p1)
                + (-p0 + p2) * t
                + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2
                + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }
}
