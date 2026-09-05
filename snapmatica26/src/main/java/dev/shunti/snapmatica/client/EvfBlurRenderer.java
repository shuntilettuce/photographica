package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
//? if >=1.21.10 {
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL43;
//?}

import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Two-pass separable Gaussian blur with per-pixel depth-of-field.
 * Ported from Photographica's EvfBlurRenderer.
 *
 * captureDepth() must be called once per frame during LevelRenderEvents.LAST.
 * renderBlur() is called from ViewfinderOverlay during HUD rendering.
 */
@Environment(EnvType.CLIENT)
public final class EvfBlurRenderer {
    private EvfBlurRenderer() {}

    private static int program  = -1;
    private static int auxFbo   = -1;
    private static int auxTex   = -1;
    private static int auxW     = 0;
    private static int auxH     = 0;
    private static int vao      = -1;
    private static int vbo      = -1;

    // Focus peaking — a separate tiny program so it can never perturb the DoF gather it
    // shares a framebuffer with. See applyPeaking() / evf_peaking.fsh.
    private static int peakProgram      = -1;
    private static int peakLocIn        = -1;
    private static int peakLocDepth     = -1;
    private static int peakLocPass      = -1;
    private static int peakLocPixelSize = -1;
    private static int peakLocFocusDist = -1;
    private static int peakLocAfMode    = -1;
    private static int peakLocNear      = -1;
    private static int peakLocFar       = -1;
    private static int peakLocColor     = -1;

    // Low-res near-field buffers at 1/NEAR_DOWNSCALE resolution. The scene is split into a
    // FOREGROUND layer (A) and a BACKGROUND layer (B), each blurred with a huge cheap
    // Gaussian (C is the separable ping-pong temp). The background blur fills the holes the
    // foreground left, so when the blurred foreground is composited its translucent edge
    // dissolves into the scene behind it — no defined silhouette.
    private static int nearFboA = -1, nearTexA = -1;   // foreground
    private static int nearFboB = -1, nearTexB = -1;   // background (holes filled)
    private static int nearFboC = -1, nearTexC = -1;   // separable-blur temp
    private static int nearW = 0, nearH = 0;
    // 1/2 rather than 1/4. At quarter resolution the wide Gaussian below had so few texels to
    // work with that a defocused foreground read as smeared paint rather than as bokeh — the
    // "ネットリ" look. Half resolution keeps the layer cheap (it is a separable blur over a
    // quarter of the pixels of the full-res gather) while giving the smear enough detail to
    // stay recognisable as an out-of-focus object.
    private static final int NEAR_DOWNSCALE = 2;

    /**
     * Whether the low-res near-field layer runs at all.
     *
     * <p>Off. It selects foreground by thresholding CoC, and that threshold draws a contour
     * across any surface whose CoC sweeps through it — a straight line in screen space
     * following nothing in the scene. A wide threshold band traded that line for washed-out
     * colour; a narrow one traded it back for a sharper line. The seam IS the mechanism, so
     * no setting removes it. The full-res gather now sizes its radius to the local blur,
     * feathers its disc edge and reports its own sampling confidence, so it stands alone.
     *
     * <p>What is lost is the "very close foreground dissolves with no defined edge" look the
     * layer existed to produce. Set true to bring it, and its seam, back.
     */
    private static final boolean NEAR_FIELD_LAYER = false;

    /** See the HandNearBlocks uniform: the line between the held item and the nearest the world
     *  can be. */
    private static final float HAND_NEAR_BLOCKS = 0.2f;

    private static int depthTex  = -1;
    static int depthTexW = 0;
    static int depthTexH = 0;

    private static int writeBackFbo   = -1;
    //? if >=1.21.10 {
    private static int centerReadFbo  = -1;
    //?}

    private static int noiseTex      = -1;
    private static int locInSampler  = -1;
    private static int locDepthSamp  = -1;
    private static int locNoiseSamp  = -1;
    private static int locBlurDir    = -1;
    private static int locPixelSize  = -1;
    private static int locFocusDist  = -1;
    private static int locAfMode     = -1;
    private static int locNearDownscale = -1;
    private static int locNearLayer  = -1;
    private static int locNoiseRot   = -1;
    private static int locNoiseOffset= -1;
    private static int locMaxBlurPx  = -1;
    private static int locSampleBoost = -1;
    private static int locCaptureHQ  = -1;
    private static int locNear       = -1;
    private static int locFar        = -1;
    private static int locFocalLen   = -1;
    private static int locAperture   = -1;
    private static int locPxPerMm    = -1;
    private static int locDofScale   = -1;
    private static int locDistortK   = -1;
    private static int locAspect     = -1;
    private static int locDoGather   = -1;
    private static int locMotionRot  = -1;
    private static int locMotionVel  = -1;
    private static int locMoveCount  = -1;
    private static int locMoveMin    = -1;
    private static int locMoveMax    = -1;
    private static int locMoveVel    = -1;
    private static final int MAX_MOVERS = 8;
    private static int locFocalPx    = -1;
    private static int locPass       = -1;
    private static int locNearSamp   = -1;
    private static int locBgSamp     = -1;
    private static int locDynRange   = -1;
    private static int locExposureGain = -1;
    private static int locDynRangeStops = -1;
    private static int locCaK        = -1;
    private static int locWbGain     = -1;
    private static int locLiveDepthSamp = -1;
    private static int locHandMask   = -1;
    private static int locHandNearBlocks = -1;

    /**
     * Millimetres of subject distance per Minecraft block — the scale the thin-lens maths
     * treats the world at, and the single strongest control over how much everything blurs.
     *
     * <p>Smaller means the world is a smaller model, so subjects are optically nearer, so the
     * depth of field is shallower. At the original 200 (1 block = 20 cm) a subject 5 blocks
     * off sat 1 m from the lens and a fast prime obliterated everything around it — the
     * tabletop-miniature look, not a camera's. 500 (1 block = 50 cm) is the compromise: a
     * block still reads as smaller than a real metre, which is how a Minecraft city is
     * actually built, without the world collapsing to a diorama. Raise toward 1000 for
     * literal real-world scale and correspondingly deeper focus.
     */
    public static final float DOF_SCALE_STILL = 375.0f;   // 1 block = 37.5 cm
    public static final float DOF_SCALE_VIDEO = 1000.0f;  // 1 block = 1 m  (unused; see above)

    /**
     * Largest circle of confusion the current optics can produce anywhere in the scene, in
     * framebuffer pixels. Serves two purposes: it decides whether there is any defocus worth
     * rendering at all, and it sizes the gather.
     *
     * <p>This replaced a flat "no blur at f/8 or narrower" rule, which is not how a lens works.
     * The circle of confusion goes as f squared over N, so stopping down is only half the
     * story — a 400 mm at f/8 throws a background far further out of focus than a 24 mm at
     * f/1.4 ever could. Cutting the blur off by f-number alone meant every long lens went
     * uniformly, unnaturally sharp the moment it passed f/8.
     *
     * <p>Evaluated at the two extremes of the depth range, since the worst defocus is always
     * at one end or the other.
     */
    public static float maxCocPx(float focusDist, float aperture, float focalLenMm,
                                 float dofScaleMm, float pxPerMm) {
        float a = cocPxAt(0.3f,  focusDist, aperture, focalLenMm, dofScaleMm, pxPerMm);
        float b = cocPxAt(Math.max(currentDepthFar, 64.0f),
                                 focusDist, aperture, focalLenMm, dofScaleMm, pxPerMm);
        return Math.max(a, b);
    }

    
    
    /** Thin-lens CoC in pixels for one subject distance — the shader's formula, on the CPU. */
    private static float cocPxAt(float depthBlocks, float focusDist, float aperture,
                                 float focalLenMm, float dofScaleMm, float pxPerMm) {
        float depthM = Math.max(depthBlocks, 0.05f);
        float cocMM;
        if (focusDist >= 99999.0f) {
            cocMM = (focalLenMm * focalLenMm) / (aperture * depthM * dofScaleMm);
        } else {
            float s1mm  = focusDist * dofScaleMm;
            float denom = aperture * Math.max(s1mm - focalLenMm, 1.0f);
            cocMM = (focalLenMm * focalLenMm) * Math.abs(depthM - focusDist) / (depthM * denom);
        }
        // Diffraction floor, added in quadrature exactly as the shader does — otherwise the
        // CPU-side ceiling would fall below the blur the shader actually produces at narrow
        // apertures, and clamp away the softening that is the whole point of modelling it.
        float airyMM = 2.44f * 0.00055f * aperture;
        return (float) Math.sqrt(cocMM * cocMM + airyMM * airyMM) * pxPerMm;
    }

    /** GLSL's smoothstep, for driving shader-side ramps from CPU-computed values. */
    private static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3.0f - 2.0f * t);
    }

    private static float clamp01(float v) {
        return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
    }

    /**
     * Radial distortion coefficient for a focal length. Positive is barrel, negative pincushion.
     *
     * <p>Scaled by (1/f - 1/f0) rather than linearly in f, because distortion is a property of
     * how wide the field is, and field angle goes with the reciprocal of focal length. A linear
     * curve made 8 mm barely worse than 14 mm, when in reality the two are nothing alike.
     *
     * <p>Rough corner displacement it produces: 8 mm ~20%, 14 mm ~11%, 24 mm ~5%, 35 mm ~2%,
     * nothing at 50 mm, and a couple of percent of pincushion by 200 mm. Real rectilinear
     * lenses are corrected below these figures; this is deliberately toward the visible end,
     * since the bow is the whole point of putting an ultra-wide on.
     */
    private static final float DISTORT_BARREL = 2.33f;   // wide end
    private static final float DISTORT_PIN    = 1.5f;    // long end, far weaker
    private static final float DISTORT_NEUTRAL_MM = 50.0f;

    public static float distortionK(float focalLenMm) {
        if (focalLenMm <= 0f) return 0f;
        float inv  = 1.0f / focalLenMm;
        float inv0 = 1.0f / DISTORT_NEUTRAL_MM;
        return (inv > inv0) ? DISTORT_BARREL * (inv - inv0)   // wider than neutral -> barrel
                            : -DISTORT_PIN   * (inv0 - inv);  // longer -> mild pincushion
    }

    /**
     * Lateral chromatic aberration for a focal length, as the fractional difference in
     * magnification between the red and blue ends of the spectrum.
     *
     * <p>Scaled by 1/f for the same reason {@link #distortionK} is: both are failures to hold a
     * wide field together, and both get dramatically worse as the field opens up rather than
     * merely proportionally worse. The constant term is what a well-corrected lens still leaves
     * behind — real CA never reaches exactly zero, it only gets small enough to stop mattering.
     *
     * <p>Roughly what it displaces at the frame's corner on a 1080p frame: 8 mm about 5 px,
     * 14 mm 3 px, 24 mm 1.8 px, 50 mm 0.8 px, 200 mm 0.3 px. Toward the visible end of what
     * real lenses do, on the same reasoning as the distortion figures above — a mod that
     * models an ultra-wide's failings should let you see them.
     */
    private static final float CA_RESIDUAL   = 0.0005f;   // what even a good lens keeps
    private static final float CA_FIELD      = 0.04f;     // how fast it grows with field angle
    private static final float CA_NEUTRAL_MM = 200.0f;

    public static float chromaticAberrationK(float focalLenMm) {
        if (!SnapmaticaClient.chromaticAberration || focalLenMm <= 0f) return 0f;
        float excess = Math.max(0f, 1.0f / focalLenMm - 1.0f / CA_NEUTRAL_MM);
        return CA_RESIDUAL + CA_FIELD * excess;
    }

    // ── Per-sample camera motion, for long-exposure smearing ─────────────────────
    private static double prevYaw, prevPitch, prevX, prevY, prevZ;
    private static boolean haveMotionRef = false;

    /**
     * How far the camera moved since the previous frame, expressed for the shader.
     *
     * <p>Measured from the last SAMPLE, not the last frame, and the reference only moves when
     * {@link #markMotionSampled()} says a sample was taken. The accumulator's interval can be
     * many frames long — a 30 s exposure samples every 250 ms — and smearing one frame's worth
     * of motion across a fifteen-frame gap leaves most of it uncovered, which is the multiple
     * exposure all over again.
     *
     * <p>Result is written into {@code outRotPx} (screen shift from turning) and
     * {@code outVelCam} (translation in camera space). Rotation shifts the whole frame
     * equally; translation shifts near things more than far ones, which is why the shader
     * divides its contribution by depth.
     */
    private static void updateCameraMotion(Minecraft mc, int fbW, int fbH,
                                           float focalPx, float[] outRotPx, float[] outVelCam) {
        outRotPx[0] = 0f; outRotPx[1] = 0f;
        outVelCam[0] = 0f; outVelCam[1] = 0f; outVelCam[2] = 0f;
        if (mc.player == null) { haveMotionRef = false; return; }

        net.minecraft.client.Camera camera = mc.gameRenderer.getMainCamera();
        double yaw = camera.yRot(), pitch = camera.xRot();
        net.minecraft.world.phys.Vec3 camPos = SnapmaticaClient.cameraPos(mc);
        double x = camPos.x, y = camPos.y, z = camPos.z;

        if (haveMotionRef) {
            motionToUniforms(yaw - prevYaw, pitch - prevPitch,
                             x - prevX, y - prevY, z - prevZ, yaw,
                             fbH, focalPx, outRotPx, outVelCam);
        }

        if (!haveMotionRef) {
            prevYaw = yaw; prevPitch = pitch; prevX = x; prevY = y; prevZ = z;
            haveMotionRef = true;
        }
    }

    /**
     * The moving entities of this sub-frame, in the camera's own frame of reference.
     *
     * <p>A mob's box and its travel go to the shader in CAMERA space, because that is the space
     * the shader can recover a pixel's position in — from {@code gl_FragCoord} and depth, with
     * no projection matrix and no convention to guess at. Built with the full basis, pitch
     * included, since a pixel's reconstructed height depends on it.
     *
     * <p>The box is the entity's own bounding box, widened by the distance it travels, so a mob
     * caught mid-slice is inside its box for the whole of it. Nothing else is widened: the wall
     * behind and the ground below are excluded by being outside in depth, which a screen-space
     * rectangle could not have done.
     *
     * @return how many movers were written
     */
    private static int uploadMovers(double[] camPose, float focalPx) {
        if (locMoveCount < 0) return 0;
        double[][] bodies = EntityExposure.movingBodies(MAX_MOVERS);
        if (bodies.length == 0) return 0;

        double yawRad   = Math.toRadians(camPose[3]);
        double pitchRad = Math.toRadians(camPose[4]);
        double cy = Math.cos(yawRad), sy = Math.sin(yawRad);
        double cp = Math.cos(pitchRad), sp = Math.sin(pitchRad);
        // Minecraft's own basis: yaw 0 looks down +Z, positive pitch looks down.
        double fx = -sy * cp, fy = -sp, fz = cy * cp;      // forward
        double rx = -cy,      ry = 0.0, rz = -sy;          // right
        double ux = ry * fz - rz * fy;                     // up = right x forward
        double uy = rz * fx - rx * fz;
        double uz = rx * fy - ry * fx;

        float[] mins = new float[MAX_MOVERS * 3];
        float[] maxs = new float[MAX_MOVERS * 3];
        float[] vels = new float[MAX_MOVERS * 3];
        int n = 0;
        for (double[] b : bodies) {
            double hw = b[3] * 0.5, h = b[4];
            double dx = b[5], dy = b[6], dz = b[7];
            // Eight corners of the box swept over the slice, in camera space.
            double lo0 = Double.MAX_VALUE, lo1 = Double.MAX_VALUE, lo2 = Double.MAX_VALUE;
            double hi0 = -Double.MAX_VALUE, hi1 = -Double.MAX_VALUE, hi2 = -Double.MAX_VALUE;
            for (int c = 0; c < 16; c++) {
                double px = b[0] + ((c & 1) == 0 ? -hw : hw) + (((c & 8) == 0) ? 0.0 : dx);
                double py = b[1] + ((c & 2) == 0 ? 0.0 : h) + (((c & 8) == 0) ? 0.0 : dy);
                double pz = b[2] + ((c & 4) == 0 ? -hw : hw) + (((c & 8) == 0) ? 0.0 : dz);
                double wx = px - camPose[0], wy = py - camPose[1], wz = pz - camPose[2];
                double X = wx * rx + wy * ry + wz * rz;
                double Y = wx * ux + wy * uy + wz * uz;
                double Z = wx * fx + wy * fy + wz * fz;
                lo0 = Math.min(lo0, X); hi0 = Math.max(hi0, X);
                lo1 = Math.min(lo1, Y); hi1 = Math.max(hi1, Y);
                lo2 = Math.min(lo2, Z); hi2 = Math.max(hi2, Z);
            }
            if (hi2 <= 0.05) continue;                     // entirely behind the camera
            double vX = dx * rx + dy * ry + dz * rz;
            double vY = dx * ux + dy * uy + dz * uz;
            double vZ = dx * fx + dy * fy + dz * fz;
            // Only worth a slot if it actually smears: below a pixel the copies already touch.
            double zMid = Math.max(0.25, 0.5 * (lo2 + hi2));
            double px = focalPx * Math.hypot(vX, vY) / zMid;
            if (px < 1.0) continue;
            mins[n * 3] = (float) lo0; mins[n * 3 + 1] = (float) lo1; mins[n * 3 + 2] = (float) lo2;
            maxs[n * 3] = (float) hi0; maxs[n * 3 + 1] = (float) hi1; maxs[n * 3 + 2] = (float) hi2;
            vels[n * 3] = (float) vX;  vels[n * 3 + 1] = (float) vY;  vels[n * 3 + 2] = (float) vZ;
            if (++n >= MAX_MOVERS) break;
        }
        if (n > 0) {
            GL20.glUniform3fv(locMoveMin, mins);
            GL20.glUniform3fv(locMoveMax, maxs);
            GL20.glUniform3fv(locMoveVel, vels);
        }
        return n;
    }



    /**
     * A camera displacement, expressed the way the shader wants it.
     *
     * <p>Rotation shifts the whole frame equally, so it becomes a pixel offset. Translation
     * shifts near things more than far ones, so it stays a camera-space vector and the shader
     * divides it by depth. Shared by the two things that need a smear — the old long-exposure
     * accumulator, which measures it between its own samples, and the aperture burst, which
     * reads it off the recorded exposure — because the smear is the same optical quantity
     * either way and only the interval differs.
     */
    private static void motionToUniforms(double dYawRaw, double dPitch,
                                         double dx, double dy, double dz, double yaw,
                                         int fbH, float focalPx,
                                         float[] outRotPx, float[] outVelCam) {
        // Yaw wraps at +-180; take the short way round or a single turn past the seam would
        // smear the entire frame.
        double dYaw = ((dYawRaw + 540.0) % 360.0) - 180.0;
        // Degrees to pixels, through the projection this frame was drawn with.
        double vFovDeg = 2.0 * Math.toDegrees(Math.atan((fbH * 0.5) / focalPx));
        double pxPerDeg = fbH / Math.max(vFovDeg, 1e-3);
        outRotPx[0] = (float) (-dYaw * pxPerDeg);
        outRotPx[1] = (float) (dPitch * pxPerDeg);

        // World delta into camera space: forward is where the player is looking.
        double yawRad = Math.toRadians(yaw);
        double sin = Math.sin(yawRad), cos = Math.cos(yawRad);
        double right   =  dx * cos - dz * sin;
        double forward =  dx * sin + dz * cos;
        // Scene shifts opposite to the camera.
        outVelCam[0] = (float) -right;
        outVelCam[1] = (float)  dy;
        outVelCam[2] = (float)  forward;
    }

    /**
     * Moves the motion reference to now. Called by the accumulator immediately after it takes
     * a sample, so the next smear covers exactly the gap that sample opened.
     */
    public static void markMotionSampled() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) { haveMotionRef = false; return; }
        net.minecraft.client.Camera camera = mc.gameRenderer.getMainCamera();
        prevYaw = camera.yRot();   prevPitch = camera.xRot();
        net.minecraft.world.phys.Vec3 camPos = SnapmaticaClient.cameraPos(mc);
        prevX   = camPos.x;     prevY = camPos.y; prevZ = camPos.z;
        haveMotionRef = true;
    }

    /** Vanilla's near plane; overridden from the live projection matrix when it differs. */
    private static final float NEAR = 0.05f;
    public static float currentDepthNear = NEAR;
    public static float currentDepthFar = 512.0f;
    private static final int GL_TEXTURE_COMPARE_MODE = 0x884C;

    /**
     * Derives the TRUE far plane that generated the depth buffer and stores it in
     * {@link #currentDepthFar}. The heuristic this replaced (renderDistance * 64) ignored
     * the fact that LOD mods (Voxy, DH) push the projection far plane out to many thousands
     * of blocks to draw distant terrain. Linearising the depth buffer with a far that is too
     * SMALL makes distant terrain read as much CLOSER than it is, which in turn gives it a
     * large circle of confusion — so LOD terrain stayed blurred even at infinity focus.
     *
     * <p>Sources are tried in descending order of trustworthiness:
     * <ol>
     *   <li>{@code projection} — the matrix actually used for the world render. Callers must
     *       pass the REAL one ({@code GameRenderer.getProjectionMatrix} on 1.21.11+), not
     *       {@code getBasicProjectionMatrix}, which merely reconstructs a matrix from vanilla
     *       parameters and so misses a LOD mod's far-plane extension entirely.</li>
     *   <li>{@code gameFarPlane} — {@code GameRenderer.getFarPlaneDistance()}, a direct read
     *       of the value vanilla feeds into that matrix.</li>
     *   <li>{@code fallbackFar} — the render-distance heuristic, last resort.</li>
     * </ol>
     */
    public static void updateDepthFar(org.joml.Matrix4f projection, float gameFarPlane,
                                      float fallbackFar) {
        float far = -1.0f;
        if (projection != null) {
            try {
                float pf = projection.perspectiveFar();
                if (isPlausibleFar(pf)) far = pf;
                // The near plane matters more than it looks: linearisation divides by
                // (Far + Near - ndc*(Far - Near)), so a wrong Near skews every distance in
                // the far field. Vanilla's is 0.05, but a shader pipeline is free to change
                // it, and we would never notice — so take it from the same matrix as Far.
                float pn = projection.perspectiveNear();
                if (Float.isFinite(pn) && pn > 0.0f && pn < 16.0f) currentDepthNear = pn;
            } catch (Throwable ignored) {}
        }
        if (far < 0.0f && isPlausibleFar(gameFarPlane)) far = gameFarPlane;
        if (far < 0.0f) far = fallbackFar;

        // Log only on a meaningful change — this runs every frame. Makes a LOD mod's
        // far-plane extension (or the failure to see it) visible without a debug build.
        if (lastLoggedFar < 0.0f || far < lastLoggedFar * 0.9f || far > lastLoggedFar * 1.1f) {
            lastLoggedFar = far;
            System.out.println("[Snapmatica] depth planes: near=" + currentDepthNear
                    + "  far=" + far + " blocks");
        }
        currentDepthFar = far;
    }

    /** An infinite-far projection yields Infinity; a stale/garbage matrix yields nonsense. */
    private static boolean isPlausibleFar(float f) {
        return Float.isFinite(f) && f > 16.0f && f < 1_000_000.0f;
    }

    private static float lastLoggedFar = -1.0f;

    /** GPU-side depth buffer copy. Call during LevelRenderEvents.LAST. */
    public static void captureDepth(int fbW, int fbH) {
        //? if >=1.21.10 {
        // In 1.21.11, GameRenderer clears the depth texture before HUD rendering,
        // so we can't borrow the GL ID — we must copy before it gets cleared.
        com.mojang.blaze3d.pipeline.RenderTarget mainFb_ =
                net.minecraft.client.Minecraft.getInstance().getMainRenderTarget();
        if (mainFb_ == null) return;
        com.mojang.blaze3d.textures.GpuTexture depthGpu_ = mainFb_.getDepthTexture();
        if (!(depthGpu_ instanceof com.mojang.blaze3d.opengl.GlTexture glDepth_)) return;
        int srcDepthId_ = glDepth_.glId();
        if (srcDepthId_ <= 0) return;
        int fw_ = mainFb_.width;
        int fh_ = mainFb_.height;
        if (fw_ <= 0 || fh_ <= 0) return;
        int prevActiveTU_ = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int prevTex2D_    = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        if (depthTex == -1 || depthTexW != fw_ || depthTexH != fh_) {
            if (depthTex != -1) GL11.glDeleteTextures(depthTex);
            depthTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
            // Match the scene depth attachment's internal format (DEPTH32 =
            // GL_DEPTH_COMPONENT32, fixed-point — NOT 32F). glCopyImageSubData
            // requires both textures to share a format size class, so a 32F copy
            // target silently fails (GL_INVALID_OPERATION), leaving garbage depth.
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT32,
                    fw_, fh_, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_UNSIGNED_INT,
                    (java.nio.ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, 0);
            depthTexW = fw_;
            depthTexH = fh_;
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex2D_);
        GL13.glActiveTexture(prevActiveTU_);
        GL43.glCopyImageSubData(
                srcDepthId_, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                depthTex,    GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                fw_, fh_, 1);
        //?} else {
        /*if (fbW <= 0 || fbH <= 0) return;

        int prevActiveTU = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int prevTex2D    = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);

        if (depthTex == -1) {
            depthTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, 0);
        } else {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
        }

        if (fbW != depthTexW || fbH != depthTexH) {
            GL11.glCopyTexImage2D(GL11.GL_TEXTURE_2D, 0,
                    GL30.GL_DEPTH_COMPONENT32F, 0, 0, fbW, fbH, 0);
            depthTexW = fbW;
            depthTexH = fbH;
        } else {
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, fbW, fbH);
        }

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex2D);
        GL13.glActiveTexture(prevActiveTU);
        *///?}
    }

    /**
     * Applies depth-aware two-pass Gaussian blur to the viewfinder area.
     * The per-pixel circle of confusion is computed in the shader from a
     * physical thin-lens model (focal length, aperture, focus distance), so
     * maxBlurPx here is only a performance ceiling on the kernel radius.
     */
    public static void renderBlur(int fx, int fy, int fx2, int fy2,
                                  float focusDist, float aperture, float focalLenMm,
                                  float dofScaleMm, boolean gpuAutoFocus) {
        renderBlur(fx, fy, fx2, fy2, focusDist, aperture, focalLenMm, dofScaleMm,
                  gpuAutoFocus, false);
    }

    public static void renderBlur(int fx, int fy, int fx2, int fy2,
                                  float focusDist, float aperture, float focalLenMm,
                                  float dofScaleMm, boolean gpuAutoFocus, boolean captureHQ) {
        renderBlur(fx, fy, fx2, fy2, focusDist, aperture, focalLenMm, dofScaleMm,
                  gpuAutoFocus, captureHQ, false);
    }

    /**
     * @param captureHQ Spends far more of the gather's sample budget than the viewfinder ever
     *                  could, on the one frame a shutter press actually keeps. A live preview
     *                  redraws dozens of times a second and has to stay cheap everywhere; a
     *                  photograph is one frame, and can afford what a real shutter does — a
     *                  moment of lag most people would not begrudge for a visibly cleaner
     *                  result, particularly on the extreme-CoC content (a heavily scaled-down
     *                  world, or a receding surface still climbing toward the lens's max blur)
     *                  where the ordinary tap ceiling stays visibly grainy against a real,
     *                  dithered Minecraft texture.
     * @param showPeaking Draws focus peaking over the result — see {@link #applyPeaking}.
     *                  Never true for a capture; it is a viewfinder aid, not something that
     *                  belongs in a saved photo or a recorded frame.
     */
    public static void renderBlur(int fx, int fy, int fx2, int fy2,
                                  float focusDist, float aperture, float focalLenMm,
                                  float dofScaleMm, boolean gpuAutoFocus, boolean captureHQ,
                                  boolean showPeaking) {
        renderBlur(fx, fy, fx2, fy2, focusDist, aperture, focalLenMm, dofScaleMm,
                  gpuAutoFocus, captureHQ, showPeaking, false);
    }

    /**
     * @param skipSensorPost Skips Pass 5/6 — white balance and DynamicRangeSim's shadow/
     *                  highlight crush — entirely for this draw. See the call site in {@link
     *                  #applyBlur} and {@code PhotoCapture.isDngCapturePending}. True only for
     *                  the GPU frame(s) that are actually becoming a DNG capture, where both
     *                  are carried as metadata instead; false (the two shorter overloads above)
     *                  everywhere else, so the viewfinder preview and a PNG/JPG capture see
     *                  both exactly as before this existed.
     */
    public static void renderBlur(int fx, int fy, int fx2, int fy2,
                                  float focusDist, float aperture, float focalLenMm,
                                  float dofScaleMm, boolean gpuAutoFocus, boolean captureHQ,
                                  boolean showPeaking, boolean skipSensorPost) {
        renderBlur(fx, fy, fx2, fy2, focusDist, aperture, focalLenMm, dofScaleMm,
                gpuAutoFocus, captureHQ, showPeaking, skipSensorPost, false, Float.NaN,
                SnapmaticaClient.sensorHeightMm());
    }

    /**
     * @param lensOnly   true for the ambient mode: depth of field only, with the distortion and
     *                   chromatic aberration that belong to the camera's lens left off.
     * @param boostOverride the gather's sample-budget ramp to force, or NaN to let the blur
     *                   size decide it as usual. Negative lowers the ceiling below the 128-tap
     *                   base — see SAMPLES_LOW in the shader.
     * @param sensorHeightMm the frame height the optics are measured against. The camera's own
     *                   (crop factor and all) for a photograph; a plain full frame for the
     *                   ambient mode, whose whole point is to be independent of the camera's
     *                   settings — and which anchors its focal length to full frame too, so
     *                   taking the height from anywhere else would leave the two disagreeing
     *                   and make the ambient blur jump whenever the CAMERA's sensor was
     *                   changed.
     */
    public static void renderBlur(int fx, int fy, int fx2, int fy2,
                                  float focusDist, float aperture, float focalLenMm,
                                  float dofScaleMm, boolean gpuAutoFocus, boolean captureHQ,
                                  boolean showPeaking, boolean skipSensorPost,
                                  boolean lensOnly, float boostOverride,
                                  float sensorHeightMm) {
        if (depthTex == -1) return;

        Minecraft mc = Minecraft.getInstance();
        RenderTarget mainFb = mc.getMainRenderTarget();
        //? if >=1.21.10 {
        com.mojang.blaze3d.textures.GpuTexture gpuTex = mainFb.getColorTexture();
        if (!(gpuTex instanceof com.mojang.blaze3d.opengl.GlTexture glTex)) return;
        int mainTex = glTex.glId();
        //?} else {
        /*int mainTex = mainFb.getColorTexture();
        *///?}
        if (mainTex == 0) return;

        //? if >=1.21.10 {
        int liveDepthTex = (mainFb.getDepthTexture()
                instanceof com.mojang.blaze3d.opengl.GlTexture glDepthLive)
                ? glDepthLive.glId() : 0;
        //?} else {
        /*int liveDepthTex = mainFb.getDepthTexture();
        *///?}

        int fbW = mainFb.width;
        int fbH = mainFb.height;
        if (fbW <= 0 || fbH <= 0) return;

        // Kernel ceiling derived from the optics rather than from the f-number. It was
        // min(240 / aperture, 120), which truncated a telephoto's bokeh for no physical
        // reason — at f/22 it capped the disc at 11 px however long the lens was. Taking the
        // largest CoC the current focal length, aperture and focus distance can actually
        // produce lets a long lens spread as far as it should, and keeps the gather tight
        // when the optics genuinely cannot blur much.
        //
        // The remaining ceiling is a FRACTION OF THE FRAME, not 120 px.
        //
        // It was 120 px on the grounds that the direct disc gather undersamples into grain
        // beyond it. Measured, that is backwards: the gather spends a fixed 128 taps whatever
        // its radius, and clamping the circle of confusion makes the noise WORSE, not better,
        // because the clamp inflates the opacity it is estimating. Against an offline
        // thin-lens render of a leaf whose true CoC was 303 px, a 120 px ceiling gave 0.23
        // opacity where 0.033 was correct — seven times too dense, ending at a hard edge
        // 160 px out — and a per-pixel spread of 0.029; lifting the ceiling past the true CoC
        // gave 0.033, flat, out to where the real disc reaches, and a spread of 0.010. Cost
        // was flat across the whole range at 1080p, since the tap count never changed.
        //
        // The clamp is also what kept a heavily defocused foreground findable at all. Once
        // every foreground pixel is pinned to the same radius, the near field's coverage is
        // just the silhouette dilated by that radius — a scaled copy of the shape, opaque in
        // the middle of any mass wider than the ceiling. The outline survived the defocus
        // because the ceiling put it back.
        //
        // A fraction of the frame rather than a pixel count, because the CoC is physically a
        // fraction of the sensor: at 1080p a 120 px ceiling clamped anything past a ninth of
        // the frame height, so the same shot got visibly worse the higher the resolution went.
        // Three quarters of the frame height leaves the optics term in charge in every case
        // that matters and costs 4-7% of the gather pass at 1080p.
        float sensorH   = sensorHeightMm;
        float pxPerMm   = fbH / sensorH;   // the frame's height in mm maps to fbH px
        float maxBlurPx = Math.min(
                maxCocPx(focusDist, aperture, focalLenMm, dofScaleMm, pxPerMm), fbH * 0.75f);
        // How much to raise the gather's tap ceiling above its 128-tap default, 0..1.
        //
        // A wide gather starves the same way whatever put it there — a fast long lens, or a
        // world scaled down to where "far" arrives after a few blocks instead of a few hundred
        // — so this reads MaxBlurPx itself rather than any one of its causes. An everyday shot
        // never nears the ramp: measured on a fence a lens-length from the camera, 128 taps and
        // 192 read the same 0.41 levels of grain, and this stays at 0 well past that (the ramp
        // does not begin until 150 px, four times the ceiling a fast 50 mm prime reaches at any
        // sane distance). It is only a receding surface whose defocus is still climbing toward
        // the lens's asymptotic maximum — the exact place a world scaled to a centimetre a
        // block puts most of midground — that pushes MaxBlurPx past a few hundred pixels and
        // asks for it. Measured there (the same transition, 128 vs 512 taps): 1.28 levels of
        // grain against 0.39, the same sqrt(N) falloff the fence test showed, just needed here
        // instead of only at the extreme.
        float sampleBoost = Float.isNaN(boostOverride)
                ? smoothstep(150.0f, 450.0f, maxBlurPx)
                : boostOverride;
        // Sub-pixel defocus is not worth a full gather — and this, not an f-number rule, is
        // the only reason to skip the blur.
        boolean anyBlur    = maxBlurPx >= 1.0f;
        // Distortion is applied by the same pass, so the pass has to run even when there is no
        // defocus at all. Bailing out on blur alone would have made an ultra-wide's barrel
        // vanish the moment it was stopped down — losing the one thing that identifies it.
        float   distortK   = lensOnly ? 0f : distortionK(focalLenMm);
        boolean anyDistort = Math.abs(distortK) >= 1e-4f;
        // The sensor-side passes (white balance, DynamicRangeSim) do not depend on the gather
        // or the distortion at all, so they have to keep the pass alive on their own — a 50 mm
        // (no distortion) stopped down far enough to have no defocus left would otherwise have
        // bailed out here and silently taken the tone curve and the colour correction with it.
        float[] wbGain    = SnapmaticaClient.whiteBalanceGain();
        // Always, unless this frame is becoming a DNG. The pass carries the exposure, the tone
        // curve and the highlight rolloff as well as white balance and the dynamic-range curve
        // now, and the first three apply to every ordinary frame — there is no configuration
        // where it has nothing to do.
        boolean anyPost   = !skipSensorPost;
        float   caK       = lensOnly ? 0f : chromaticAberrationK(focalLenMm);
        boolean anyCa     = caK >= 1e-6f;
        if (!anyBlur && !anyDistort && !anyPost && !anyCa) return;
        if (!anyBlur) maxBlurPx = 1.0f;   // keep the gather's radii trivial

        ensureInit(fbW, fbH);
        if (program == -1) return;

        // Drain any GL errors left by Iris / DH / Voxy before we touch the context, so a
        // pre-existing error flag can't be (mis)attributed to our draws and, conversely,
        // so our own work starts from a clean slate.
        for (int e = 0; e < 32 && GL11.glGetError() != GL11.GL_NO_ERROR; e++) { /* drain */ }

        int prevProgram  = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevFbo      = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevArrayBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int prevVao      = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevActiveTU = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int prevTex0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        //? if >=1.21.10 {
        // 1.21.11 binds sampler objects per texture unit (GlCommandEncoder.glBindSampler)
        // that persist after MC's draws. Our shader would sample through those instead of
        // the texture's own parameters, reading garbage. Unbind so our glTexParameteri wins.
        int prevSampler0 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL33.glBindSampler(0, 0);
        //?}
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        int prevTex1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        //? if >=1.21.10 {
        int prevSampler1 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL33.glBindSampler(1, 0);
        //?}
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        int prevTex2 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        //? if >=1.21.10 {
        int prevSampler2 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL33.glBindSampler(2, 0);
        //?}
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        int prevTex3 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        //? if >=1.21.10 {
        int prevSampler3 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL33.glBindSampler(3, 0);
        //?}
        GL13.glActiveTexture(GL13.GL_TEXTURE4);
        int prevTex4 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        //? if >=1.21.10 {
        int prevSampler4 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL33.glBindSampler(4, 0);
        //?}
        GL13.glActiveTexture(GL13.GL_TEXTURE5);
        int prevTex5 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        //? if >=1.21.10 {
        int prevSampler5 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL33.glBindSampler(5, 0);
        //?}
        int[] prevViewport   = new int[4];
        int[] prevScissorBox = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT,    prevViewport);
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, prevScissorBox);
        boolean scissorWasEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean depthWasEnabled   = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blendWasEnabled   = GL11.glIsEnabled(GL11.GL_BLEND);

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        GL20.glUseProgram(program);
        GL30.glBindVertexArray(vao);

        GL20.glUniform1i(locInSampler, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
        GL20.glUniform1i(locDepthSamp, 1);
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, noiseTex);
        GL20.glUniform1i(locNoiseSamp, 2);
        GL20.glUniform1i(locNearSamp, 3);   // foreground bound to unit 3 before composite
        GL20.glUniform1i(locBgSamp, 4);     // background bound to unit 4 before composite

        // frame and does not need it, since nothing is being kept.
        // Unit 5: the scene depth as it stands right now, against which DepthSampler's copy is
        // compared to find the held item — see drawnAfterDepthCopy in the shader. Only bound
        // for the ambient mode; the camera hides the hand outright, so it has nothing to mask.
        boolean handMask = lensOnly && liveDepthTex > 0;
        if (handMask) {
            GL13.glActiveTexture(GL13.GL_TEXTURE5);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, liveDepthTex);
            GL20.glUniform1i(locLiveDepthSamp, 5);
        }
        GL20.glUniform1i(locHandMask, handMask ? 1 : 0);
        // Anything this close is the held item, not the world — see the shader's own note. The
        // figure sits between the band Iris reserves for the hand (about 0.09 to 0.11 blocks)
        // and the nearest a block face can actually get to the eye (about 0.3), so it separates
        // the two with room either side, and finds nothing at all when the hand is not in the
        // depth copy to begin with.
        GL20.glUniform1f(locHandNearBlocks, handMask ? HAND_NEAR_BLOCKS : 0.0f);

        GL20.glUniform1f(locFocusDist, focusDist);
        GL20.glUniform1i(locAfMode, gpuAutoFocus ? 1 : 0);
        GL20.glUniform1i(locNearDownscale, NEAR_DOWNSCALE);
        GL20.glUniform1i(locNearLayer, NEAR_FIELD_LAYER ? 1 : 0);
        // Turn the gather's sampling disc by a different angle on every sub-frame of a burst.
        //
        // The rotation is otherwise drawn from a blue-noise texture keyed on gl_FragCoord
        // alone, which is the right choice for a single frame and exactly the wrong one for
        // sixty-four: the same pixel gets the same pattern every time, so the gather's
        // under-sampling does not average out across the sum — it is reinforced by it, and what
        // should have resolved into bokeh stays a fixed smear with the noise's own texture
        // printed through it. A golden-angle step per sub-frame decorrelates them, which is what
        // lets the accumulation clean up the gather instead of stacking its artefacts.
        // The live average needs this every bit as much as the burst does: averaging k frames
        // divides the gather's grain by sqrt(k) only if the grain is INDEPENDENT between them,
        // and a fixed noise tile gives every frame the same pattern to average with itself.
        int noiseIdx = ApertureIntegration.isActive()
                     ? ApertureIntegration.sampleIndex() : -1;
        GL20.glUniform1f(locNoiseRot, noiseIdx >= 0 ? noiseIdx * 2.39996323f : 0.0f);
        // And a fresh noise VALUE per pixel per sub-frame, not just a fresh angle. The R2
        // low-discrepancy sequence spreads 64 offsets over the tile far more evenly than
        // random ones would, so no two sub-frames land on nearly the same pattern.
        if (noiseIdx >= 0) {
            GL20.glUniform2f(locNoiseOffset, (noiseIdx * 0.7548777f) % 1.0f,
                                             (noiseIdx * 0.5698403f) % 1.0f);
        } else {
            GL20.glUniform2f(locNoiseOffset, 0.0f, 0.0f);
        }
        GL20.glUniform1f(locMaxBlurPx, maxBlurPx);
        GL20.glUniform1f(locSampleBoost, sampleBoost);
        GL20.glUniform1f(locCaptureHQ, captureHQ ? 1.0f : 0.0f);
        GL20.glUniform1f(locNear, currentDepthNear);
        GL20.glUniform1f(locFar, currentDepthFar);
        GL20.glUniform1f(locFocalLen, focalLenMm);
        GL20.glUniform1f(locAperture, aperture);
        GL20.glUniform1f(locPxPerMm, pxPerMm);      // the frame's height in mm maps to fbH px
        GL20.glUniform1f(locDofScale, dofScaleMm);
        GL20.glUniform1f(locDistortK, distortK);
        GL20.glUniform1i(locDoGather, anyBlur ? 1 : 0);
        // Applied to the photo and the recorded frame too, not just the viewfinder — unlike
        // peaking this is meant to be part of the resulting image, not a composing aid, so it
        // is read directly from the setting rather than threaded through forCapture/showPeaking.
        GL20.glUniform1i(locDynRange, SnapmaticaClient.dynamicRangeSim ? 1 : 0);
        // Same EV-based gain PhotoProcessor.exposureFactor() brightens the SAVED photo with —
        // reused here so the tone curve reacts to the same exposure the settings actually call
        // for. Without this, crush/rolloff read straight off Minecraft's own render brightness,
        // which has no idea whether the aperture/shutter/ISO the shot is dialled in for is
        // exposing for the dark interior of a cave or the bright entrance behind it — a cave
        // properly exposed for its own interior should blow the sunlit opening out, not the
        // other way round, and that only happens if the curve knows how much brighter the
        // camera is being asked to render before it decides what counts as a highlight.
        GL20.glUniform1f(locExposureGain, (float) PhotoProcessor.exposureFactor());
        GL20.glUniform1f(locDynRangeStops, SnapmaticaClient.dynamicRangeStops);
        GL20.glUniform1f(locCaK, caK);
        GL20.glUniform3f(locWbGain, wbGain[0], wbGain[1], wbGain[2]);

        // Motion smear only during a long exposure. A fast shutter IS one instant, so freezing
        // the action is the correct answer there, not blurring it.
        // Focal length in PIXELS: half the frame height over the tangent of the half vertical
        // field, so the anchor is the frame's own half-height in mm — 12 mm at full frame, less
        // on a cropped sensor. The same anchor GameRendererMixin uses to set the field of view,
        // which is what makes these agree.
        // Image distance rather than focal length, so this tracks the same field of view
        // GameRendererMixin actually rendered with once focus breathing is on.
        // Image distance rather than focal length, so this tracks the field of view actually
        // rendered once focus breathing is on. The ambient mode is excluded: breathing is the
        // CAMERA's focus ring moving its image plane, and this mode has no ring — its focal
        // length already comes from the projection the frame was really drawn with.
        float imageDistMm = lensOnly ? Math.max(focalLenMm, 1)
                : (float) SnapmaticaClient.imageDistanceMm(Math.max(focalLenMm, 1));
        float focalPx = (fbH * 0.5f) / ((sensorH * 0.5f) / imageDistMm);
        float[] rotPx = new float[2], velCam = new float[3];
        double[] burstMotion = ApertureIntegration.isActive()
                ? EntityExposure.cameraSliceDelta() : null;
        int movers = 0;
        if (burstMotion != null) {
            // A pupil sample is also a TIME sample, and it has to stand for its slice of the
            // exposure or the burst averages instants instead of integrating an interval —
            // which is a multiple exposure, and reads as the picture doubling along the pan.
            // Taken from the recorded path rather than frame to frame, so the pupil excursion
            // is not in it: that one is the aperture's, and the burst already integrates it.
            motionToUniforms(burstMotion[3], burstMotion[4],
                             burstMotion[0], burstMotion[1], burstMotion[2], burstMotion[5],
                             fbH, focalPx, rotPx, velCam);
            double[] pose = EntityExposure.cameraFor();
            if (pose != null) movers = uploadMovers(pose, focalPx);
            haveMotionRef = false;
        } else if (PhotoCapture.isLongExposing()) {
            updateCameraMotion(mc, fbW, fbH, focalPx, rotPx, velCam);
        } else {
            haveMotionRef = false;
        }
        GL20.glUniform2f(locMotionRot, rotPx[0], rotPx[1]);
        GL20.glUniform3f(locMotionVel, velCam[0], velCam[1], velCam[2]);
        if (locMoveCount >= 0) GL20.glUniform1i(locMoveCount, movers);
        GL20.glUniform1f(locFocalPx, focalPx);
        GL20.glUniform1f(locAspect, (float) fbW / (float) fbH);

        // ── Near-field passes (scissor still disabled; whole low-res buffer) ──────────
        if (NEAR_FIELD_LAYER) {
        // These run over the entire 1/4-res near texture so the foreground blur isn't
        // clipped at the viewfinder edge; the composite below is what scissors to frame.
        GL20.glUniform2f(locPixelSize, 1.0f / nearW, 1.0f / nearH);

        GL11.glViewport(0, 0, nearW, nearH);

        // Extract FOREGROUND (premultiplied) at low res: main → A.
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, nearFboA);
        GL20.glUniform1i(locPass, 1);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, mainTex);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        // Disc convolution on the foreground — the aperture's own shape, so a defocused
        // silhouette rounds off instead of merely softening. A disc is not separable, so this
        // is a single 2-D pass (A → C) rather than the H/V pair a Gaussian allows. It runs at
        // 1/NEAR_DOWNSCALE resolution, which is what keeps 64 taps affordable.
        GL20.glUniform1i(locPass, 2);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, nearFboC);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, nearTexA);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        // Extract BACKGROUND (complement, premultiplied): main → B.
        GL20.glUniform1i(locPass, 3);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, nearFboB);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, mainTex);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        // Fixed wide Gaussian on the background — it bleeds into the foreground holes (fill):
        // B →(H)→ A →(V)→ B. A is free to be the scratch buffer now: the disc pass above
        // consumed the raw foreground extract out of it and left its result in C, which must
        // survive until the composite reads it as NearSampler.
        GL20.glUniform1i(locPass, 4);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, nearFboA);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, nearTexB);
        GL20.glUniform2f(locBlurDir, 1.0f, 0.0f);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, nearFboB);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, nearTexA);
        GL20.glUniform2f(locBlurDir, 0.0f, 1.0f);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        }   // end near-field passes

        // ── Full-res passes (scissored to viewfinder + bleed) ────────────────────────
        GL20.glUniform2f(locPixelSize, 1.0f / fbW, 1.0f / fbH);

        double scale = mc.getWindow().getGuiScale();
        int scX = (int)(fx  * scale);
        int scY = fbH - (int)(fy2 * scale);
        int scW = (int)((fx2 - fx) * scale);
        int scH = (int)((fy2 - fy) * scale);
        // The distorting copy pass reads from displaced coordinates, which for a wide lens
        // reach well outside the viewfinder rectangle — and outside the scissor the aux buffer
        // holds nothing this frame. Widen to the whole framebuffer rather than try to predict
        // the reach; the gather's own per-pixel early-out keeps the extra area cheap.
        int bleed = anyDistort ? Math.max(fbW, fbH) : (int) maxBlurPx;
        int expX = Math.max(0, scX - bleed);
        int expY = Math.max(0, scY - bleed);
        int expW = Math.min(fbW - expX, scW + 2 * bleed);
        int expH = Math.min(fbH - expY, scH + 2 * bleed);

        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(expX, expY, expW, expH);

        // Gather: 3-layer disc bokeh, main → aux. (Pass 0, BlurDir.x = 1 → gather.)
        GL20.glUniform1i(locPass, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, auxFbo);
        GL11.glViewport(0, 0, fbW, fbH);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, mainTex);
        GL20.glUniform2f(locBlurDir, 1.0f, 0.0f);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        // Composite: denoise(aux) with the near-field over it, → main. (BlurDir.x = 0.)
        if (writeBackFbo == -1) writeBackFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, writeBackFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, mainTex, 0);
        GL11.glViewport(0, 0, fbW, fbH);
        GL11.glScissor(expX, expY, expW, expH);
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, nearTexC);   // foreground (disc-blurred)
        GL13.glActiveTexture(GL13.GL_TEXTURE4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, nearTexB);   // filled background
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, auxTex);
        GL20.glUniform2f(locBlurDir, 0.0f, 0.0f);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        // Lateral chromatic aberration, BEFORE peaking — it is the lens, so it happens to the
        // light on its way to the sensor, long before any finder overlay is drawn over the
        // result. Applied as its own resample rather than folded into the distorting composite
        // above (which would compose the two displacements into a single texture read, and be
        // marginally sharper for it) because the composite has three separate sampling branches
        // and each would have to triple its taps to carry three wavelengths; one extra bilinear
        // read of an already-filtered image is the cheaper half of that trade by a wide margin.
        if (anyCa) {
            GL20.glUseProgram(program);
            GL20.glUniform1i(locPass, 7);   // apply CA, mainTex -> auxTex
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, auxFbo);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, mainTex);
            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
            GL20.glUniform1i(locPass, 6);   // plain copy back, auxTex -> mainTex
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, writeBackFbo);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, auxTex);
            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        }

        depthFresh = false;   // consumed by this draw, whichever path asked for it

        if (showPeaking && peakProgram != -1) {
            applyPeaking(mainTex, fbW, fbH, focusDist, gpuAutoFocus, writeBackFbo);
        }

        // The sensor-side steps — white balance, then DynamicRangeSim's crush/rolloff — as one
        // final pass AFTER peaking, not folded into the composite draw above, so peaking's edge
        // detector sees the real, uncrushed frame instead of one already flattened where it
        // most needs the contrast (see evf_blur.fsh's own doc on Pass 5/6 for the full
        // reasoning).
        //
        // Skipped entirely when skipSensorPost is set — a DNG capture frame, where BOTH of
        // these are exactly the kind of irreversible, already-decided-for-you step the DNG path
        // exists to avoid baking in (a raw file carries its white balance as metadata — see
        // DngWriter's AsShotNeutral — precisely so the developer can change their mind about
        // it). They run here, on the GPU, BEFORE PhotoCapture's CPU code ever sees the
        // framebuffer, so there is no later point downstream where skipping them would still be
        // possible — they have to not happen at all, not merely not be re-applied.
        if (anyPost) {
            GL20.glUseProgram(program);
            // Pass 5: white balance, then the curve, mainTex -> auxTex.
            GL20.glUniform1i(locPass, 5);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, auxFbo);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, mainTex);
            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
            // Pass 6: plain copy back, auxTex -> mainTex.
            GL20.glUniform1i(locPass, 6);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, writeBackFbo);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, auxTex);
            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        }

        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, 0, 0);

        // Restore GL state
        if (!scissorWasEnabled) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(prevScissorBox[0], prevScissorBox[1], prevScissorBox[2], prevScissorBox[3]);
        GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        if (depthWasEnabled) GL11.glEnable(GL11.GL_DEPTH_TEST);
        if (blendWasEnabled) GL11.glEnable(GL11.GL_BLEND);
        GL13.glActiveTexture(GL13.GL_TEXTURE5);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex5);
        //? if >=1.21.10 {
        GL33.glBindSampler(5, prevSampler5);
        //?}
        GL13.glActiveTexture(GL13.GL_TEXTURE4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex4);
        //? if >=1.21.10 {
        GL33.glBindSampler(4, prevSampler4);
        //?}
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex3);
        //? if >=1.21.10 {
        GL33.glBindSampler(3, prevSampler3);
        //?}
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex2);
        //? if >=1.21.10 {
        GL33.glBindSampler(2, prevSampler2);
        //?}
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex1);
        //? if >=1.21.10 {
        GL33.glBindSampler(1, prevSampler1);
        //?}
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex0);
        //? if >=1.21.10 {
        GL33.glBindSampler(0, prevSampler0);
        //?}
        GL13.glActiveTexture(prevActiveTU);
        GL30.glBindVertexArray(prevVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevArrayBuf);
        GL20.glUseProgram(prevProgram);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);

        // Drain any error WE produced so it can't surface inside a later DH / Voxy GL call
        // (their async LOD buffer churn runs on this same render thread; a lingering error
        // flag can push the NVIDIA driver into the glDeleteBuffers crash seen in reports).
        for (int e = 0; e < 32 && GL11.glGetError() != GL11.GL_NO_ERROR; e++) { /* drain */ }
    }

    /**
     * Draws focus peaking over the frame the DoF pass just finished, in place. Two passes
     * because a texture can't be read and written by the same draw call — feedback loop, and
     * undefined which one wins on any given GPU: detect + highlight reads {@code mainTex} and
     * writes {@code auxTex} (free at this point — the gather already consumed it), then a
     * plain copy lands the result back in {@code mainTex}, where the rest of the pipeline (and
     * the screenshot, on the rare frame this runs on despite being asked not to) expects the
     * finished frame to be.
     */
    private static void applyPeaking(int mainTex, int fbW, int fbH, float focusDist,
                                     boolean gpuAutoFocus, int writeBackFbo) {
        GL20.glUseProgram(peakProgram);
        GL20.glUniform1i(peakLocIn, 0);
        GL20.glUniform1i(peakLocDepth, 1);
        GL20.glUniform2f(peakLocPixelSize, 1.0f / fbW, 1.0f / fbH);
        GL20.glUniform1f(peakLocFocusDist, focusDist);
        GL20.glUniform1i(peakLocAfMode, gpuAutoFocus ? 1 : 0);
        GL20.glUniform1f(peakLocNear, currentDepthNear);
        GL20.glUniform1f(peakLocFar, currentDepthFar);
        GL20.glUniform3f(peakLocColor, 1.0f, 0.2f, 0.05f);   // warm red-orange, reads against most scenes

        // Pass 0: detect + highlight, mainTex -> auxTex.
        GL20.glUniform1i(peakLocPass, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, auxFbo);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, mainTex);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        // Pass 1: plain copy back, auxTex -> mainTex.
        GL20.glUniform1i(peakLocPass, 1);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, writeBackFbo);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, auxTex);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        GL20.glUseProgram(program);
    }

    //? if >=1.21.10 {
    public static float readCenterLinearDepthBlocks() {
        if (depthTex == -1 || depthTexW <= 0 || depthTexH <= 0) return -1.0f;
        if (centerReadFbo == -1) centerReadFbo = GL30.glGenFramebuffers();
        int prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, centerReadFbo);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, depthTex, 0);
        // Read a small centre patch and keep the NEAREST real (non-sky) sample. A single
        // centre pixel can slip through a gap between distant LOD quads and read the sky
        // behind them, inflating the focus distance; the nearest-of-patch locks onto the
        // subject the reticle is actually over.
        final int N = 3;
        int x0 = Math.max(0, depthTexW / 2 - N / 2);
        int y0 = Math.max(0, depthTexH / 2 - N / 2);
        FloatBuffer buf = BufferUtils.createFloatBuffer(N * N);
        GL11.glReadPixels(x0, y0, N, N, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, buf);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
        float rawD = 1.0f;
        for (int i = 0; i < N * N; i++) {
            float d = buf.get(i);
            if (d > 0.001f && d < rawD) rawD = d;
        }
        if (rawD >= 0.999999f) return SnapmaticaClient.FOCUS_INFINITY;  // sky / beyond far plane
        if (rawD < 0.001f) return -1.0f;
        return currentDepthNear * currentDepthFar
                / (currentDepthFar - rawD * (currentDepthFar - currentDepthNear));
    }
    //?}

    /**
     * Applies the depth-of-field and distortion pass, deciding for itself whether it should.
     *
     * <p>There is no longer a schedule. Parameters used to be recorded during the HUD pass and
     * applied on the NEXT frame, because the HUD cannot issue raw GL — but the rectangle is the
     * only thing the HUD ever knew, and it is just a function of the window size, so the whole
     * detour was avoidable. The lag it introduced was visible: the field of view changes on the
     * frame the viewfinder opens or the zoom moves, while the distortion arrived a frame later,
     * so the image snapped to the new angle undistorted and bowed immediately after.
     *
     * <p>Called from GameRendererMixin straight after renderWorld, so the optics it reads are
     * the same ones that frame was rendered with.
     *
     * @param forCapture blur the FULL framebuffer, because the photo crop reaches past the
     *                   viewfinder frame and a scissored pass would leave its edges sharp.
     *                   True for every frame a capture is pending, including every sample a
     *                   long exposure accumulates.
     * @param captureHQ  spend far more of the gather's sample budget, because this is the one
     *                   frame a fast shutter is going to keep. Deliberately a SEPARATE question
     *                   from forCapture: a long exposure's samples all need forCapture's
     *                   full-frame region, but none of them should get this — they already
     *                   converge by being averaged together, and paying per-sample for extra
     *                   spatial taps on top would be redundant and slow enough to turn a
     *                   several-second exposure into the better part of a minute.
     */
    public static void applyBlur(boolean forCapture, boolean captureHQ) {
        // Cleared before anything can return early — this runs exactly once per frame.
        boolean ambientAlreadyDone = ambientDoneThisFrame;
        ambientDoneThisFrame = false;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || SnapmaticaClient.lensType == 0) return;

        // Same predicate the viewfinder draws itself by, so the two cannot disagree about
        // whether the camera is up.
        boolean viewfinderUp = SnapmaticaClient.viewfinderActive(mc) && mc.screen == null;
        if (!viewfinderUp && !PhotoCapture.isCapturePending()) {
            // viewfinderActive rather than viewfinderUp: the camera owns the optics whenever it
            // is raised, screen open or not. viewfinderUp additionally requires no screen, so
            // testing it here would let the ambient lens cut in the moment the settings screen
            // was opened while sneaking — swapping the optics under the player mid-adjustment.
            // Matches the guard in SnapmaticaClient.updateAmbientFocus, so the focus this uses
            // and the frame it draws can never disagree about which mode is running.
            if (!ambientAlreadyDone
                    && SnapmaticaClient.ambientDof
                    && !SnapmaticaClient.viewfinderActive(mc)
                    && !VideoRecorder.isRecording()) {
                applyAmbientBlur(mc);
                // Reached here the held item is already in the frame, so the shader's
                // drawnAfterDepthCopy mask is what has to keep it sharp.
                logAmbientPath("post-world (mask must catch the held item)");
            }
            return;
        }

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int x0 = 0, y0 = 0, x1 = sw, y1 = sh;
        if (!forCapture) {
            int[] fr = PhotoCapture.frameRect(sw, sh, SnapmaticaClient.portraitOrientation);
            x0 = fr[0]; y0 = fr[1]; x1 = fr[0] + fr[2]; y1 = fr[1] + fr[3];
        }
        // GPU autofocus is wired but off — Voxy's LOD terrain leaves no usable depth in the
        // vanilla buffer, so sampling it changes nothing. Flip to test another LOD mod.
        // Peaking is a viewfinder aid, never baked into the photo or a recorded frame — it
        // has no business surviving in something you keep.
        boolean showPeaking = !forCapture && SnapmaticaClient.focusPeaking;
        // DynamicRangeSim's crush/rolloff (Pass 5/6, below) runs on the GPU BEFORE PhotoCapture
        // ever reads the framebuffer back — so it is already unrecoverably baked in by the time
        // a screenshot readback could otherwise skip it, the same way PhotoCapture's own CPU
        // steps are. Only skip it for a frame that is ACTUALLY becoming a DNG capture (forCapture
        // alone isn't enough — that's also true while VideoRecorder is recording, which this
        // must not affect) — see PhotoCapture.isDngCapturePending.
        // Also skipped for every sub-frame of an aperture burst — the dynamic-range curve, the
        // tone curve and the highlight rolloff are the sensor reading one finished exposure,
        // and averaging two hundred separately-curved partial ones is a different function.
        // ApertureIntegration.finish applies them once, to the completed sum.
        boolean skipSensorPost = ApertureIntegration.isActive()
                || (forCapture && PhotoCapture.isDngCapturePending());
        // During an aperture burst the gather is not turned off — it is turned DOWN, to the
        // one cell of the pupil this sub-frame stands for. See
        // ApertureIntegration.subApertureFNumber: the cells tile the pupil, so their blurs sum
        // to the full aperture's, and each one covers exactly the gap to its neighbour that
        // would otherwise show up as a ghost. The focus comes from the burst too, so the
        // gather and the shear cannot end up registered against different planes.
        boolean burst = ApertureIntegration.isActive();
        float blurFocus = burst ? ApertureIntegration.latchedFocusBlocks()
                                : AutoFocus.shaderFocusDistance();
        float blurAperture = burst ? ApertureIntegration.subApertureFNumber()
                                   : SnapmaticaClient.aperture;
        renderBlur(x0, y0, x1, y1, blurFocus,
                blurAperture, SnapmaticaClient.focalLengthMm,
                SnapmaticaClient.dofScaleMm, false, captureHQ, showPeaking, skipSensorPost);
    }

    /**
     * Whether the depth texture was refreshed during THIS frame's world render.
     *
     * <p>Set by {@code PhotoCapture.onBeforeTranslucent} and consumed once by the ambient path
     * below. The depth copy and the blur that reads it are gated in two different places, and
     * when those two gates disagreed the blur did not fail loudly — it silently ran against a
     * depth image from an entirely different camera position, which looks like broken optics
     * rather than like a missing update. A blur with no matching depth is now simply not drawn.
     */
    private static boolean depthFresh = false;

    static void markDepthFresh() { depthFresh = true; }

    /**
     * Whether the ambient blur already ran this frame, from the pre-hand hook below rather than
     * from {@link #applyBlur}. Cleared at the top of every {@link #applyBlur}, which runs once
     * a frame regardless, so exactly one ambient pass happens either way.
     */
    private static boolean ambientDoneThisFrame = false;

    /** Throttles the one diagnostic line below to roughly once every five seconds. */
    private static long ambientLogMs = 0L;
    private static String ambientLastPath = "";

    /**
     * Applies the ambient blur before the held item is drawn, when the pipeline lets us.
     *
     * <p>Belt and braces with {@code drawnAfterDepthCopy} in the shader, deliberately. The two
     * fail in different circumstances: this one needs the held item to be drawn where vanilla
     * draws it (Sodium and Iris can move that), and the mask needs the item to actually leave a
     * mark in the live depth buffer (which is a guess about a pipeline we do not control). One
     * of them holding is enough, and neither interferes with the other — at this point in the
     * frame vanilla has just cleared the depth buffer and the hand is not drawn yet, so the
     * mask finds nothing to mask and simply does nothing.
     */
    public static void applyAmbientBlurBeforeHand() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || SnapmaticaClient.lensType == 0) return;
        if (!SnapmaticaClient.ambientDof) return;
        if (SnapmaticaClient.viewfinderActive(mc) || VideoRecorder.isRecording()) return;
        if (PhotoCapture.isCapturePending()) return;
        if (!depthFresh) return;   // no depth for this frame yet; the fallback will handle it
        applyAmbientBlur(mc);
        ambientDoneThisFrame = true;
        logAmbientPath("pre-hand");
    }

    /**
     * One line, at most every five seconds and only when the answer changes. Which of the two
     * routes actually carries the ambient blur decides whether the held item can be kept sharp
     * by ordering or has to be masked, and that depends on mods this cannot see.
     */
    private static void logAmbientPath(String path) {
        long now = System.currentTimeMillis();
        if (path.equals(ambientLastPath) && now - ambientLogMs < 5000L) return;
        ambientLastPath = path;
        ambientLogMs = now;
        System.out.println("[Snapmatica] ambient DoF applied via " + path);
    }

    /**
     * The ambient depth of field: the lens applied to ordinary play rather than to a photograph.
     *
     * <p>Runs only when the camera is not up — {@link #applyBlur} hands over here after it has
     * ruled out the viewfinder, a capture and a recording, so the two never composite over each
     * other. Full-screen rather than scissored to the photo frame, because there is no frame:
     * this IS the view.
     *
     * <p>Every parameter is the ambient mode's own (see {@link SnapmaticaClient#ambientDof}),
     * and three things the camera path does are deliberately skipped:
     * <ul>
     *   <li><b>The sensor pass</b> (exposure, white balance, the dynamic-range curve, the tone
     *       curve, the highlight rolloff) — those belong to the photograph. Nobody wants their
     *       game re-exposed because a camera dial is somewhere.
     *   <li><b>Distortion and chromatic aberration</b> — properties of the lens the camera is
     *       carrying. Bowing and fringing the view someone is playing through is a different
     *       proposition from doing it to a picture they chose to take.
     *   <li><b>Focus peaking</b> — a manual-focus aid, and there is no focus ring here.
     * </ul>
     *
     * <p>Focal length is not a setting: it is READ OFF the projection the frame was actually
     * rendered with. Whatever field of view the game is drawing at IS a focal length on a given
     * frame size, so {@code f = halfFrameHeight * proj[1][1]} is the honest answer and it tracks
     * the player's own FOV slider, sprinting, and a spyglass for free. Anchored to full frame
     * rather than to the camera's sensor setting, since that setting belongs to the camera.
     */
    private static void applyAmbientBlur(Minecraft mc) {
        // Only ever against depth captured for the frame being drawn — see depthFresh.
        boolean fresh = depthFresh;
        depthFresh = false;
        if (!fresh) return;

        org.joml.Matrix4f proj = PhotoCapture.worldProjection(mc);
        if (proj == null) return;
        float m11 = proj.m11();
        if (!(m11 > 0.01f)) return;   // no usable projection this frame
        // 12 mm is half of a 24 mm full-frame height — see the note above on why this does not
        // read the camera's own sensor setting.
        float focalMm = Math.max(1.0f, 12.0f * m11);

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        // Performance / balanced / high, as a downward ramp on the gather's tap ceiling. A
        // photograph is one frame and a viewfinder is a few seconds; this is every frame
        // forever, so it starts well below the 128 the other two take for granted.
        float boost = switch (SnapmaticaClient.ambientQuality) {
            case 0  -> -1.0f;    // toward SAMPLES_LOW
            case 2  ->  0.0f;    // the ordinary 128-tap base
            default -> -0.5f;
        };

        renderBlur(0, 0, sw, sh,
                SnapmaticaClient.ambientFocusDistance,
                SnapmaticaClient.ambientAperture,
                focalMm,
                SnapmaticaClient.ambientDofScaleMm,
                false,      // gpuAutoFocus: the CPU ray in SnapmaticaClient.updateAmbientFocus owns it
                false,      // captureHQ
                false,      // showPeaking
                true,       // skipSensorPost — exposure/WB/tone belong to the camera
                true,       // ambient: no distortion, no chromatic aberration
                boost,
                24.0f);     // full frame, matching the focal length derived above
    }

    /**
     * Reads the captured depth texture back to the CPU as linearised depth in blocks.
     * Uses the texture's own dimensions so shader mods (Iris, etc.) that cause the GL
     * viewport to differ from the framebuffer texture size don't suppress the readback.
     */
    public static float[] readLinearDepthCpu() {
        if (depthTex == -1 || depthTexW <= 0 || depthTexH <= 0) return null;
        int fbW = depthTexW, fbH = depthTexH;
        java.nio.FloatBuffer buf = BufferUtils.createFloatBuffer(fbW * fbH);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, buf);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        final float near = currentDepthNear;
        final float far  = currentDepthFar;
        float[] linear = new float[fbW * fbH];
        for (int i = 0; i < linear.length; i++) {
            float d   = buf.get(i);
            float ndc = 2.0f * d - 1.0f;
            linear[i] = 2.0f * near * far / (far + near - ndc * (far - near));
        }
        return linear;
    }

    private static void ensureInit(int fbW, int fbH) {
        if (program == -1) initProgram();
        if (peakProgram == -1) initPeakProgram();
        if (auxFbo == -1 || auxW != fbW || auxH != fbH) initAux(fbW, fbH);
        int nw = Math.max(1, fbW / NEAR_DOWNSCALE);
        int nh = Math.max(1, fbH / NEAR_DOWNSCALE);
        if (nearFboA == -1 || nearW != nw || nearH != nh) initNear(nw, nh);
        if (noiseTex == -1) initNoise();
    }

    /** Low-res RGBA8 ping-pong pair for the near-field foreground (LINEAR so the composite
     *  upsamples it smoothly). */
    private static void initNear(int w, int h) {
        if (nearFboA != -1) {
            GL30.glDeleteFramebuffers(nearFboA); GL11.glDeleteTextures(nearTexA);
            GL30.glDeleteFramebuffers(nearFboB); GL11.glDeleteTextures(nearTexB);
            GL30.glDeleteFramebuffers(nearFboC); GL11.glDeleteTextures(nearTexC);
        }
        int[] tex = new int[3];
        int[] fbo = new int[3];
        for (int i = 0; i < 3; i++) {
            int t = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            int f = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, f);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, t, 0);
            tex[i] = t; fbo[i] = f;
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        nearTexA = tex[0]; nearFboA = fbo[0];
        nearTexB = tex[1]; nearFboB = fbo[1];
        nearTexC = tex[2]; nearFboC = fbo[2];
        nearW = w; nearH = h;
    }

    /** Upload the bundled 64x64 blue-noise dither (raw single-channel bytes) as a GL_R8
     *  texture used to rotate each pixel's gather samples. */
    private static void initNoise() {
        byte[] data;
        try (InputStream is = EvfBlurRenderer.class.getResourceAsStream(
                "/assets/snapmatica/textures/evf_bluenoise.bin")) {
            if (is == null) { System.err.println("[Snapmatica] blue-noise texture missing"); return; }
            data = is.readAllBytes();
        } catch (Exception e) {
            System.err.println("[Snapmatica] blue-noise load failed: " + e);
            return;
        }
        if (data.length < 128 * 128) return;
        java.nio.ByteBuffer buf = BufferUtils.createByteBuffer(128 * 128);
        buf.put(data, 0, 128 * 128).flip();

        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevUnpack = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        noiseTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, noiseTex);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, 128, 128, 0,
                GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, buf);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, prevUnpack);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
    }

    private static void initProgram() {
        try {
            String vshSrc = readResource("/assets/snapmatica/shaders/evf_blur.vsh");
            String fshSrc = readResource("/assets/snapmatica/shaders/evf_blur.fsh");

            int vs = compileShader(GL20.GL_VERTEX_SHADER,   "evf_blur.vsh", vshSrc);
            int fs = compileShader(GL20.GL_FRAGMENT_SHADER, "evf_blur.fsh", fshSrc);
            if (vs == -1 || fs == -1) return;

            int prog = GL20.glCreateProgram();
            GL20.glAttachShader(prog, vs);
            GL20.glAttachShader(prog, fs);
            GL20.glBindAttribLocation(prog, 0, "Position");
            GL20.glBindAttribLocation(prog, 1, "UV0");
            GL20.glLinkProgram(prog);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);

            if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                System.err.println("[Snapmatica] EvfBlur link error: " + GL20.glGetProgramInfoLog(prog));
                GL20.glDeleteProgram(prog);
                return;
            }

            program      = prog;
            locInSampler = GL20.glGetUniformLocation(program, "InSampler");
            locDepthSamp = GL20.glGetUniformLocation(program, "DepthSampler");
            locNoiseSamp = GL20.glGetUniformLocation(program, "NoiseSampler");
            locBlurDir   = GL20.glGetUniformLocation(program, "BlurDir");
            locPixelSize = GL20.glGetUniformLocation(program, "PixelSize");
            locFocusDist = GL20.glGetUniformLocation(program, "FocusDist");
            locAfMode    = GL20.glGetUniformLocation(program, "AfMode");
            locNearDownscale = GL20.glGetUniformLocation(program, "NearDownscale");
            locNearLayer = GL20.glGetUniformLocation(program, "NearLayer");
            locNoiseRot  = GL20.glGetUniformLocation(program, "NoiseRot");
            locNoiseOffset = GL20.glGetUniformLocation(program, "NoiseOffset");
            locMaxBlurPx = GL20.glGetUniformLocation(program, "MaxBlurPx");
            locSampleBoost = GL20.glGetUniformLocation(program, "SampleBoost");
            locCaptureHQ   = GL20.glGetUniformLocation(program, "CaptureHQ");
            locNear      = GL20.glGetUniformLocation(program, "Near");
            locFar       = GL20.glGetUniformLocation(program, "Far");
            locFocalLen  = GL20.glGetUniformLocation(program, "FocalLenMm");
            locAperture  = GL20.glGetUniformLocation(program, "Aperture");
            locPxPerMm   = GL20.glGetUniformLocation(program, "PxPerMm");
            locDofScale  = GL20.glGetUniformLocation(program, "DofScale");
            locCaK       = GL20.glGetUniformLocation(program, "CaK");
            locLiveDepthSamp = GL20.glGetUniformLocation(program, "LiveDepthSampler");
            locHandMask  = GL20.glGetUniformLocation(program, "HandMask");
            locHandNearBlocks = GL20.glGetUniformLocation(program, "HandNearBlocks");
            locWbGain    = GL20.glGetUniformLocation(program, "WbGain");
            locDistortK  = GL20.glGetUniformLocation(program, "DistortK");
            locAspect    = GL20.glGetUniformLocation(program, "Aspect");
            locDoGather  = GL20.glGetUniformLocation(program, "DoGather");
            locMotionRot = GL20.glGetUniformLocation(program, "MotionRotPx");
            locMotionVel = GL20.glGetUniformLocation(program, "MotionVelCam");
            locMoveCount = GL20.glGetUniformLocation(program, "MoveCount");
            locMoveMin   = GL20.glGetUniformLocation(program, "MoveMin");
            locMoveMax   = GL20.glGetUniformLocation(program, "MoveMax");
            locMoveVel   = GL20.glGetUniformLocation(program, "MoveVel");
            locFocalPx   = GL20.glGetUniformLocation(program, "FocalPx");
            locPass      = GL20.glGetUniformLocation(program, "Pass");
            locNearSamp  = GL20.glGetUniformLocation(program, "NearSampler");
            locBgSamp    = GL20.glGetUniformLocation(program, "BgSampler");
            locDynRange  = GL20.glGetUniformLocation(program, "DynRange");
            locExposureGain = GL20.glGetUniformLocation(program, "ExposureGain");
            locDynRangeStops = GL20.glGetUniformLocation(program, "DynRangeStops");

            float[] verts = {
                -1f, -1f,  0f, 0f,
                 1f, -1f,  1f, 0f,
                -1f,  1f,  0f, 1f,
                 1f,  1f,  1f, 1f,
            };
            FloatBuffer buf = BufferUtils.createFloatBuffer(verts.length);
            buf.put(verts).flip();

            vao = GL30.glGenVertexArrays();
            GL30.glBindVertexArray(vao);
            vbo = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 0);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 2L * Float.BYTES);
            GL20.glEnableVertexAttribArray(1);
            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

            System.out.println("[Snapmatica] EvfBlurRenderer initialised");
        } catch (Exception e) {
            System.err.println("[Snapmatica] EvfBlurRenderer init failed: " + e.getMessage());
        }
    }

    /** Compiles the focus-peaking program. Shares {@link #vao}/{@link #vbo} with the main
     *  program — a fullscreen quad's attribute layout doesn't depend on which program reads it. */
    private static void initPeakProgram() {
        try {
            String vshSrc = readResource("/assets/snapmatica/shaders/evf_blur.vsh");
            String fshSrc = readResource("/assets/snapmatica/shaders/evf_peaking.fsh");

            int vs = compileShader(GL20.GL_VERTEX_SHADER,   "evf_blur.vsh (peaking)", vshSrc);
            int fs = compileShader(GL20.GL_FRAGMENT_SHADER, "evf_peaking.fsh", fshSrc);
            if (vs == -1 || fs == -1) return;

            int prog = GL20.glCreateProgram();
            GL20.glAttachShader(prog, vs);
            GL20.glAttachShader(prog, fs);
            GL20.glBindAttribLocation(prog, 0, "Position");
            GL20.glBindAttribLocation(prog, 1, "UV0");
            GL20.glLinkProgram(prog);
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);

            if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                System.err.println("[Snapmatica] EvfPeaking link error: " + GL20.glGetProgramInfoLog(prog));
                GL20.glDeleteProgram(prog);
                return;
            }

            peakProgram      = prog;
            peakLocIn        = GL20.glGetUniformLocation(peakProgram, "InSampler");
            peakLocDepth     = GL20.glGetUniformLocation(peakProgram, "DepthSampler");
            peakLocPass      = GL20.glGetUniformLocation(peakProgram, "Pass");
            peakLocPixelSize = GL20.glGetUniformLocation(peakProgram, "PixelSize");
            peakLocFocusDist = GL20.glGetUniformLocation(peakProgram, "FocusDist");
            peakLocAfMode    = GL20.glGetUniformLocation(peakProgram, "AfMode");
            peakLocNear      = GL20.glGetUniformLocation(peakProgram, "Near");
            peakLocFar       = GL20.glGetUniformLocation(peakProgram, "Far");
            peakLocColor     = GL20.glGetUniformLocation(peakProgram, "PeakColor");

            System.out.println("[Snapmatica] Focus peaking initialised");
        } catch (Exception e) {
            System.err.println("[Snapmatica] Focus peaking init failed: " + e.getMessage());
        }
    }

    private static int compileShader(int type, String name, String src) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, src);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            System.err.println("[Snapmatica] EvfBlur shader compile error [" + name + "]: "
                    + GL20.glGetShaderInfoLog(id));
            GL20.glDeleteShader(id);
            return -1;
        }
        return id;
    }

    private static void initAux(int w, int h) {
        if (auxFbo != -1) {
            GL30.glDeleteFramebuffers(auxFbo);
            GL11.glDeleteTextures(auxTex);
        }
        auxTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, auxTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        auxFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, auxFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, auxTex, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        auxW = w;
        auxH = h;
    }

    private static String readResource(String path) throws Exception {
        try (InputStream is = EvfBlurRenderer.class.getResourceAsStream(path)) {
            if (is == null) throw new RuntimeException("Resource not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
