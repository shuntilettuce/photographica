package dev.shunti.snapmatica.client;


import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Draws the recorded camera path: a smooth curve through every keyframe's position, and a
 * camera frustum (a truncated pyramid, near plane to far plane) at each one, so the shot is
 * visible while it's being built rather than only once Play is pressed.
 *
 * <p>Drawn as a 2D overlay projected onto the HUD, not as real 3D world geometry. The first
 * version used {@code ItemBlockRenderTypes.lines()} directly in the world, which worked fine under the
 * vanilla renderer but read as faint and washed out under Photon (and presumably any other
 * Iris shaderpack) — shaderpacks intercept and re-shade Minecraft's own line render layer as
 * part of their normal world lighting/tone-mapping pass, and a bright, deliberately-flat debug
 * colour is exactly what that pass is built to darken and desaturate. The HUD, unlike the
 * world, renders after the shaderpack's post-processing composite — the same reason text,
 * the crosshair and every other HUD element look the same with or without a shaderpack — so
 * projecting the path into 2D and drawing it there sidesteps the problem entirely rather than
 * fighting it. The trade-off is that this no longer occludes behind terrain the way real world
 * geometry would; for a planning aid you're actively composing a shot with, always-visible is
 * the more useful default anyway.
 *
 * <p>The view and projection are computed fresh every frame from {@code Camera.rotation()}
 * and the same focal-length FOV formula {@code GameRendererMixin} uses — an earlier version
 * captured Mojang's own matrices via a mixin on {@code LevelRenderer.render()} instead, which
 * turned out to go stale under some shaderpack render paths (Iris re-invoking that same method
 * for a shadow pass, most likely) and left the path frozen at whatever FOV was current when it
 * last legitimately fired. Building both matrices here directly needs no mixin and can never
 * observe anything other than the real camera.
 *
 * <p>1.21.11 only for now — see [[snapmatica-iterate-1-21-11-only]] in the session's own
 * workflow.
 */

public final class CameraPathRenderer {
    private CameraPathRenderer() {}

    private static final int CURVE_SEGMENTS_PER_SPAN = 12;
    private static final double FRUSTUM_NEAR = 0.15, FRUSTUM_FAR = 0.55;
    // Same 36x24 mm sensor convention as GameRendererMixin's frameFov, scaled by the same crop
    // factor so the gizmo narrows with the real field of view; always landscape here — a gizmo
    // does not need to track whichever orientation was active when the keyframe was recorded,
    // just give an honest sense of the angle of view.
    private static final double SENSOR_HALF_W = 18.0, SENSOR_HALF_H = 12.0;

    private static final int CURVE_COLOR = 0xFF64EBFF;
    private static final int CURVE_BLOCKED_COLOR = 0xFFFFA000;
    private static final int FRUSTUM_COLOR = 0xFFFF5A46;
    private static final int LINE_PX = 3;

    public static void render(net.minecraft.client.gui.GuiGraphics ctx, Object tickCounter) {
        // Not yet ported below 1.21.10 — see the class doc.
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
