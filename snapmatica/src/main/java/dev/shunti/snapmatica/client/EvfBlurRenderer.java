package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
//? if >=1.21.11 {
/*import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL43;
*///?}

import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Two-pass separable Gaussian blur with per-pixel depth-of-field.
 * Ported from Photographica's EvfBlurRenderer.
 *
 * captureDepth() must be called once per frame during WorldRenderEvents.LAST.
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

    private static int depthTex  = -1;
    static int depthTexW = 0;
    static int depthTexH = 0;

    private static int writeBackFbo   = -1;
    //? if >=1.21.11 {
    /*private static int centerReadFbo  = -1;
    *///?}

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
    private static int locMaxBlurPx  = -1;
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
    private static int locFocalPx    = -1;
    private static int locPass       = -1;
    private static int locNearSamp   = -1;
    private static int locBgSamp     = -1;

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
    private static void updateCameraMotion(MinecraftClient mc, int fbW, int fbH,
                                           float focalPx, float[] outRotPx, float[] outVelCam) {
        outRotPx[0] = 0f; outRotPx[1] = 0f;
        outVelCam[0] = 0f; outVelCam[1] = 0f; outVelCam[2] = 0f;
        if (mc.player == null) { haveMotionRef = false; return; }

        double yaw = mc.player.getYaw(), pitch = mc.player.getPitch();
        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();

        if (haveMotionRef) {
            // Yaw wraps at +-180; take the short way round or a single turn past the seam
            // would smear the entire frame.
            double dYaw = ((yaw - prevYaw + 540.0) % 360.0) - 180.0;
            double dPitch = pitch - prevPitch;
            // Degrees to pixels, through the projection this frame was drawn with.
            double vFovDeg = 2.0 * Math.toDegrees(Math.atan((fbH * 0.5) / focalPx));
            double pxPerDeg = fbH / Math.max(vFovDeg, 1e-3);
            outRotPx[0] = (float) (-dYaw * pxPerDeg);
            outRotPx[1] = (float) (dPitch * pxPerDeg);

            double dx = x - prevX, dy = y - prevY, dz = z - prevZ;
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

        if (!haveMotionRef) {
            prevYaw = yaw; prevPitch = pitch; prevX = x; prevY = y; prevZ = z;
            haveMotionRef = true;
        }
    }

    /**
     * Moves the motion reference to now. Called by the accumulator immediately after it takes
     * a sample, so the next smear covers exactly the gap that sample opened.
     */
    public static void markMotionSampled() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) { haveMotionRef = false; return; }
        prevYaw = mc.player.getYaw();   prevPitch = mc.player.getPitch();
        prevX   = mc.player.getX();     prevY = mc.player.getY(); prevZ = mc.player.getZ();
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

    /** GPU-side depth buffer copy. Call during WorldRenderEvents.LAST. */
    public static void captureDepth(int fbW, int fbH) {
        //? if >=1.21.11 {
        /*// In 1.21.11, GameRenderer clears the depth texture before HUD rendering,
        // so we can't borrow the GL ID — we must copy before it gets cleared.
        net.minecraft.client.gl.Framebuffer mainFb_ =
                net.minecraft.client.MinecraftClient.getInstance().getFramebuffer();
        if (mainFb_ == null) return;
        com.mojang.blaze3d.textures.GpuTexture depthGpu_ = mainFb_.getDepthAttachment();
        if (!(depthGpu_ instanceof net.minecraft.client.texture.GlTexture glDepth_)) return;
        int srcDepthId_ = glDepth_.getGlId();
        if (srcDepthId_ <= 0) return;
        int fw_ = mainFb_.textureWidth;
        int fh_ = mainFb_.textureHeight;
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
        *///?} else {
        if (fbW <= 0 || fbH <= 0) return;

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
        //?}
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
        if (depthTex == -1) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        Framebuffer mainFb = mc.getFramebuffer();
        //? if >=1.21.11 {
        /*com.mojang.blaze3d.textures.GpuTexture gpuTex = mainFb.getColorAttachment();
        if (!(gpuTex instanceof net.minecraft.client.texture.GlTexture glTex)) return;
        int mainTex = glTex.getGlId();
        *///?} else {
        int mainTex = mainFb.getColorAttachment();
        //?}
        if (mainTex == 0) return;

        int fbW = mainFb.textureWidth;
        int fbH = mainFb.textureHeight;
        if (fbW <= 0 || fbH <= 0) return;

        // Kernel ceiling derived from the optics rather than from the f-number. It was
        // min(240 / aperture, 120), which truncated a telephoto's bokeh for no physical
        // reason — at f/22 it capped the disc at 11 px however long the lens was. Taking the
        // largest CoC the current focal length, aperture and focus distance can actually
        // produce lets a long lens spread as far as it should, and keeps the gather tight
        // when the optics genuinely cannot blur much. 120 px remains as a perf ceiling: the
        // direct (non-mip) disc gather undersamples into grain beyond it.
        float pxPerMm   = fbH / 24.0f;   // 24 mm sensor height maps to fbH px
        float maxBlurPx = Math.min(
                maxCocPx(focusDist, aperture, focalLenMm, dofScaleMm, pxPerMm), 120.0f);
        // Sub-pixel defocus is not worth a full gather — and this, not an f-number rule, is
        // the only reason to skip the blur.
        boolean anyBlur    = maxBlurPx >= 1.0f;
        // Distortion is applied by the same pass, so the pass has to run even when there is no
        // defocus at all. Bailing out on blur alone would have made an ultra-wide's barrel
        // vanish the moment it was stopped down — losing the one thing that identifies it.
        float   distortK   = distortionK(focalLenMm);
        boolean anyDistort = Math.abs(distortK) >= 1e-4f;
        if (!anyBlur && !anyDistort) return;
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
        //? if >=1.21.11 {
        /*// 1.21.11 binds sampler objects per texture unit (GlCommandEncoder.glBindSampler)
        // that persist after MC's draws. Our shader would sample through those instead of
        // the texture's own parameters, reading garbage. Unbind so our glTexParameteri wins.
        int prevSampler0 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL33.glBindSampler(0, 0);
        *///?}
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        int prevTex1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        //? if >=1.21.11 {
        /*int prevSampler1 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL33.glBindSampler(1, 0);
        *///?}
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        int prevTex2 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        //? if >=1.21.11 {
        /*int prevSampler2 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL33.glBindSampler(2, 0);
        *///?}
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        int prevTex3 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        //? if >=1.21.11 {
        /*int prevSampler3 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL33.glBindSampler(3, 0);
        *///?}
        GL13.glActiveTexture(GL13.GL_TEXTURE4);
        int prevTex4 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        //? if >=1.21.11 {
        /*int prevSampler4 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL33.glBindSampler(4, 0);
        *///?}
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

        GL20.glUniform1f(locFocusDist, focusDist);
        GL20.glUniform1i(locAfMode, gpuAutoFocus ? 1 : 0);
        GL20.glUniform1i(locNearDownscale, NEAR_DOWNSCALE);
        GL20.glUniform1i(locNearLayer, NEAR_FIELD_LAYER ? 1 : 0);
        GL20.glUniform1f(locMaxBlurPx, maxBlurPx);
        GL20.glUniform1f(locNear, currentDepthNear);
        GL20.glUniform1f(locFar, currentDepthFar);
        GL20.glUniform1f(locFocalLen, focalLenMm);
        GL20.glUniform1f(locAperture, aperture);
        GL20.glUniform1f(locPxPerMm, fbH / 24.0f);  // 24mm sensor height maps to fbH px
        GL20.glUniform1f(locDofScale, dofScaleMm);
        GL20.glUniform1f(locDistortK, distortK);
        GL20.glUniform1i(locDoGather, anyBlur ? 1 : 0);

        // Motion smear only during a long exposure. A fast shutter IS one instant, so freezing
        // the action is the correct answer there, not blurring it.
        // Focal length in PIXELS: half the frame height over the tangent of the half vertical
        // field. The 35 mm frame is 24 mm tall, so its half-height is 12 mm — the same anchor
        // GameRendererMixin uses to set the field of view, which is what makes these agree.
        float focalPx = (fbH * 0.5f) / (float) (12.0 / Math.max(focalLenMm, 1));
        float[] rotPx = new float[2], velCam = new float[3];
        if (PhotoCapture.isLongExposing()) {
            updateCameraMotion(mc, fbW, fbH, focalPx, rotPx, velCam);
        } else {
            haveMotionRef = false;
        }
        GL20.glUniform2f(locMotionRot, rotPx[0], rotPx[1]);
        GL20.glUniform3f(locMotionVel, velCam[0], velCam[1], velCam[2]);
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

        double scale = mc.getWindow().getScaleFactor();
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

        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, 0, 0);

        // Restore GL state
        if (!scissorWasEnabled) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(prevScissorBox[0], prevScissorBox[1], prevScissorBox[2], prevScissorBox[3]);
        GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        if (depthWasEnabled) GL11.glEnable(GL11.GL_DEPTH_TEST);
        if (blendWasEnabled) GL11.glEnable(GL11.GL_BLEND);
        GL13.glActiveTexture(GL13.GL_TEXTURE4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex4);
        //? if >=1.21.11 {
        /*GL33.glBindSampler(4, prevSampler4);
        *///?}
        GL13.glActiveTexture(GL13.GL_TEXTURE3);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex3);
        //? if >=1.21.11 {
        /*GL33.glBindSampler(3, prevSampler3);
        *///?}
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex2);
        //? if >=1.21.11 {
        /*GL33.glBindSampler(2, prevSampler2);
        *///?}
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex1);
        //? if >=1.21.11 {
        /*GL33.glBindSampler(1, prevSampler1);
        *///?}
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex0);
        //? if >=1.21.11 {
        /*GL33.glBindSampler(0, prevSampler0);
        *///?}
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

    //? if >=1.21.11 {
    /*public static float readCenterLinearDepthBlocks() {
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
    *///?}

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
     *                   viewfinder frame and a scissored pass would leave its edges sharp
     */
    public static void applyBlur(boolean forCapture) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || SnapmaticaClient.lensType == 0) return;

        // Same predicate the viewfinder draws itself by, so the two cannot disagree about
        // whether the camera is up.
        boolean viewfinderUp = SnapmaticaClient.viewfinderSneakEnabled
                && mc.player.isSneaking() && mc.currentScreen == null;
        if (!viewfinderUp && !PhotoCapture.isCapturePending()) return;

        int sw = mc.getWindow().getScaledWidth();
        int sh = mc.getWindow().getScaledHeight();
        int x0 = 0, y0 = 0, x1 = sw, y1 = sh;
        if (!forCapture) {
            int[] fr = PhotoCapture.frameRect(sw, sh, SnapmaticaClient.portraitOrientation);
            x0 = fr[0]; y0 = fr[1]; x1 = fr[0] + fr[2]; y1 = fr[1] + fr[3];
        }
        // GPU autofocus is wired but off — Voxy's LOD terrain leaves no usable depth in the
        // vanilla buffer, so sampling it changes nothing. Flip to test another LOD mod.
        renderBlur(x0, y0, x1, y1, AutoFocus.shaderFocusDistance(),
                SnapmaticaClient.aperture, SnapmaticaClient.focalLengthMm,
                DOF_SCALE_STILL, false);
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
            locMaxBlurPx = GL20.glGetUniformLocation(program, "MaxBlurPx");
            locNear      = GL20.glGetUniformLocation(program, "Near");
            locFar       = GL20.glGetUniformLocation(program, "Far");
            locFocalLen  = GL20.glGetUniformLocation(program, "FocalLenMm");
            locAperture  = GL20.glGetUniformLocation(program, "Aperture");
            locPxPerMm   = GL20.glGetUniformLocation(program, "PxPerMm");
            locDofScale  = GL20.glGetUniformLocation(program, "DofScale");
            locDistortK  = GL20.glGetUniformLocation(program, "DistortK");
            locAspect    = GL20.glGetUniformLocation(program, "Aspect");
            locDoGather  = GL20.glGetUniformLocation(program, "DoGather");
            locMotionRot = GL20.glGetUniformLocation(program, "MotionRotPx");
            locMotionVel = GL20.glGetUniformLocation(program, "MotionVelCam");
            locFocalPx   = GL20.glGetUniformLocation(program, "FocalPx");
            locPass      = GL20.glGetUniformLocation(program, "Pass");
            locNearSamp  = GL20.glGetUniformLocation(program, "NearSampler");
            locBgSamp    = GL20.glGetUniformLocation(program, "BgSampler");

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
