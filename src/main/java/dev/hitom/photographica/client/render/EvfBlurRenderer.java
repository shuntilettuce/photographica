package dev.hitom.photographica.client.render;

import dev.hitom.photographica.Photographica;
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
 *
 * Each pixel's blur radius is derived from its own depth vs the current
 * focus distance, so the subject stays sharp while the background blurs.
 *
 * captureDepth() must be called once per frame (during WorldRenderEvents.LAST,
 * before Iris composites overwrite the depth buffer) when the mirrorless EVF
 * is active. It copies the depth buffer to a texture entirely on the GPU.
 *
 * renderBlur() is called from ViewfinderHud during HUD rendering.
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

    // Depth texture (GPU-side copy of scene depth buffer)
    private static int depthTex  = -1;
    private static int depthTexW = 0;
    private static int depthTexH = 0;

    // Uniform locations
    private static int noiseTex      = -1;
    private static int locInSampler  = -1;
    private static int locDepthSamp  = -1;
    private static int locNoiseSamp  = -1;
    private static int locBlurDir    = -1;
    private static int locPixelSize  = -1;
    private static int locFocusDist  = -1;
    private static int locMaxBlurPx  = -1;
    private static int locNear       = -1;
    private static int locFar        = -1;
    private static int locFocalLen   = -1;
    private static int locAperture   = -1;
    private static int locPxPerMm    = -1;
    private static int locDofScale   = -1;

    // Focus peaking: a separate small program sharing the aux FBO/VAO with the DoF gather
    // (peaking always runs after DoF in a frame, so aux is free by the time it needs it).
    private static int peakProgram     = -1;
    private static int peakLocIn       = -1;
    private static int peakLocDepth    = -1;
    private static int peakLocPass     = -1;
    private static int peakLocPixel    = -1;
    private static int peakLocFocus    = -1;
    private static int peakLocNear     = -1;
    private static int peakLocFar      = -1;
    private static int peakLocColor    = -1;
    private static final float[] PEAK_COLOR = {1.0f, 0.2f, 0.05f}; // warm red-orange

    // Drone digital zoom (see LensKind#digitalZoomSoftenPx): detail-throwing-away pass for
    // focal lengths past either sensor's native one, sharing the same aux FBO/VAO ping-pong as
    // peaking (both are single full-res passes).
    private static int zoomProgram  = -1;
    private static int zoomLocIn    = -1;
    private static int zoomLocPass  = -1;
    private static int zoomLocPixel = -1;
    private static int zoomLocBlock = -1;

    public static final float DOF_SCALE_STILL = 200.0f;   // 1 block = 20 cm (still viewfinder)
    public static final float DOF_SCALE_VIDEO = 1000.0f;  // 1 block = 1 m  (video, realistic)

    private static final float NEAR = 0.05f;
    public  static float currentDepthFar = 512.0f;

    // GL_TEXTURE_COMPARE_MODE = 0x884C, GL_NONE = 0  (OpenGL 1.4+)
    private static final int GL_TEXTURE_COMPARE_MODE = 0x884C;

    // EVF schedule→execute state (1.21.11). 1.21.11 routes HUD draws through a deferred
    // GuiRenderState that discards raw-GL framebuffer writes done during HUD rendering, so the
    // blur is scheduled from the HUD and executed right after renderWorld() — writing the result
    // straight into the scene colour texture (writeBackFbo), scissored to the viewfinder frame.
    // Works for both vanilla and Iris. Harmless/no-op on <1.21.11, which draws directly from the HUD.
    private static int writeBackFbo   = -1;
    private static boolean blurScheduled = false;
    private static int   scheduledFx, scheduledFy, scheduledFx2, scheduledFy2;
    private static float scheduledFocusDist, scheduledAperture, scheduledFocalLen;

    // Same schedule→execute split, for focus peaking.
    private static boolean peakScheduled = false;
    private static int   peakSchedFx, peakSchedFy, peakSchedFx2, peakSchedFy2;
    private static float peakSchedFocusDist;

    // Same schedule→execute split, for the drone's digital-zoom detail loss.
    private static boolean zoomScheduled = false;
    private static int   zoomSchedFx, zoomSchedFy, zoomSchedFx2, zoomSchedFy2;
    private static float zoomSchedBlockPx;

    /** Records EVF blur params to be executed after renderWorld() (1.21.11 schedule→execute). */
    public static void scheduleBlur(int fx, int fy, int fx2, int fy2,
                                    float focusDist, float aperture, float focalLenMm) {
        scheduledFx = fx; scheduledFy = fy; scheduledFx2 = fx2; scheduledFy2 = fy2;
        scheduledFocusDist = focusDist; scheduledAperture = aperture; scheduledFocalLen = focalLenMm;
        blurScheduled = true;
    }

    /** Executes a scheduled blur; no-op if nothing is scheduled. Call after renderWorld().
     *  forCapture=true blurs the WHOLE framebuffer so the saved photo's edges are covered:
     *  the 3:2 crop extends past the viewfinder frame, so a viewfinder-only scissor left a
     *  sharp unblurred band around the photo. forCapture=false keeps the live EVF scissored. */
    public static void applyScheduledBlur(boolean forCapture) {
        if (!blurScheduled) return;
        blurScheduled = false;
        if (forCapture) {
            MinecraftClient mc = MinecraftClient.getInstance();
            int sw = mc.getWindow().getScaledWidth();
            int sh = mc.getWindow().getScaledHeight();
            renderBlur(0, 0, sw, sh, scheduledFocusDist, scheduledAperture, scheduledFocalLen, DOF_SCALE_STILL);
        } else {
            renderBlur(scheduledFx, scheduledFy, scheduledFx2, scheduledFy2,
                    scheduledFocusDist, scheduledAperture, scheduledFocalLen, DOF_SCALE_STILL);
        }
    }

    /** Records EVF focus-peaking params to be executed after renderWorld() (1.21.11 schedule→execute). */
    public static void schedulePeaking(int fx, int fy, int fx2, int fy2, float focusDist) {
        peakSchedFx = fx; peakSchedFy = fy; peakSchedFx2 = fx2; peakSchedFy2 = fy2;
        peakSchedFocusDist = focusDist;
        peakScheduled = true;
    }

    /** Executes scheduled focus peaking; no-op if nothing is scheduled OR forCapture is true —
     *  peaking is a live-viewfinder manual-focus aid and must never be baked into a saved
     *  photo or recorded video frame. */
    public static void applyScheduledPeaking(boolean forCapture) {
        if (!peakScheduled) return;
        peakScheduled = false;
        if (forCapture) return;
        applyPeaking(peakSchedFx, peakSchedFy, peakSchedFx2, peakSchedFy2, peakSchedFocusDist);
    }

    /** Records the drone's digital-zoom detail loss to be executed after renderWorld() (1.21.11
     *  schedule→execute) — see {@link #applyDigitalZoom}. */
    public static void scheduleDigitalZoom(int fx, int fy, int fx2, int fy2, float blockPx) {
        zoomSchedFx = fx; zoomSchedFy = fy; zoomSchedFx2 = fx2; zoomSchedFy2 = fy2;
        zoomSchedBlockPx = blockPx;
        zoomScheduled = true;
    }

    /** Executes a scheduled digital-zoom softening; no-op if nothing is scheduled OR forCapture
     *  is true — this is a live-viewfinder-only effect. The saved photo gets the equivalent
     *  softening applied CPU-side instead (see {@code PhotoCapture#applyDigitalSoftening}), so
     *  running this on a capture frame too would degrade the photo twice over. */
    public static void applyScheduledDigitalZoom(boolean forCapture) {
        if (!zoomScheduled) return;
        zoomScheduled = false;
        if (forCapture) return;
        applyDigitalZoom(zoomSchedFx, zoomSchedFy, zoomSchedFx2, zoomSchedFy2, zoomSchedBlockPx);
    }

    /**
     * Derives the TRUE far plane from the live world projection matrix. LOD mods (Voxy, DH)
     * extend the projection far plane to draw distant terrain; using the correct far makes
     * depth linearisation accurate so AF distance and EVF DoF blur match reality.
     */
    public static void updateDepthFar(org.joml.Matrix4f projection, float fallbackFar) {
        float far = fallbackFar;
        if (projection != null) {
            try {
                float pf = projection.perspectiveFar();
                if (Float.isFinite(pf) && pf > 16.0f && pf < 1_000_000.0f) far = pf;
            } catch (Throwable ignored) {}
        }
        currentDepthFar = far;
    }

    /**
     * Copies the current framebuffer's depth buffer into a texture (GPU-side, no
     * CPU readback). Must be called during WorldRenderEvents.LAST while the scene
     * depth buffer is still intact (before Iris composites).
     */
    public static void captureDepth(int fbW, int fbH) {
        //? if >=1.21.11 {
        /*// In 1.21.11, GameRenderer clears the depth texture before HUD rendering, so we
        // can't borrow the GL ID at draw time — we must copy it now, before it's cleared.
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
            // GL_DEPTH_COMPONENT32, fixed-point — NOT 32F). glCopyImageSubData requires
            // both textures to share a format size class, so a 32F copy target silently
            // fails (GL_INVALID_OPERATION), leaving garbage depth.
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
            // Ensure depth texture is sampled as raw float, not shadow comparison
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, 0);
        } else {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
        }

        if (fbW != depthTexW || fbH != depthTexH) {
            // (Re-)allocate and copy
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
     * Each pixel's blur radius is derived from its depth vs focusDist,
     * so in-focus pixels stay sharp while out-of-focus pixels blur.
     *
     * fx/fy/fx2/fy2 are in scaled GUI coordinates.
     */
    public static void applyVideoBlur(float focusDist, float aperture, float focalLenMm,
                                      float dofScaleMm) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int sw = mc.getWindow().getScaledWidth();
        int sh = mc.getWindow().getScaledHeight();
        renderBlur(0, 0, sw, sh, focusDist, aperture, focalLenMm, dofScaleMm);
    }

    public static void renderBlur(int fx, int fy, int fx2, int fy2,
                                  float focusDist, float aperture, float focalLenMm,
                                  float dofScaleMm) {
        if (depthTex == -1) return; // depth not captured yet

        MinecraftClient mc = MinecraftClient.getInstance();
        Framebuffer mainFb = mc.getFramebuffer();
        //? if >=1.21.11 {
        /*com.mojang.blaze3d.textures.GpuTexture gpuTex = mainFb.getColorAttachment();
        if (gpuTex == null) return;
        if (!(gpuTex instanceof net.minecraft.client.texture.GlTexture)) {
            Photographica.LOGGER.warn("[EvfBlurRenderer] Unexpected GpuTexture type {} — skipping EVF blur.",
                    gpuTex.getClass().getName());
            return;
        }
        int mainTex = ((net.minecraft.client.texture.GlTexture) gpuTex).getGlId();
        *///?} else {
        int mainTex = mainFb.getColorAttachment();
        //?}
        if (mainTex == 0) return;

        int fbW = mainFb.textureWidth;
        int fbH = mainFb.textureHeight;
        if (fbW <= 0 || fbH <= 0) return;

        // CoC is a physical mm quantity mapped through PxPerMm, so its pixel cap has to be a
        // fraction of the frame — a fixed pixel ceiling truncates telephoto/close-focus bokeh
        // circles well before they'd naturally taper off, turning smooth bokeh into a flat
        // disc with a visible edge (a fixed cap sized for 1080p also clips harder at 4K).
        float maxBlurPx = Math.min(240.0f / aperture, fbH * 0.75f);
        if (maxBlurPx < 0.5f) return;

        ensureInit(fbW, fbH);
        if (program == -1) return;

        // ---- Save GL state ----
        int prevProgram  = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevFbo      = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevVao      = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevActiveTU = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int prevTex0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        //? if >=1.21.11 {
        /*// 1.21.11 binds sampler objects per texture unit (GlCommandEncoder.glBindSampler)
        // that persist after MC's draws. Our shader would sample through those instead of
        // the texture's own parameters, reading garbage (→ black viewfinder). Unbind so our
        // glTexParameteri settings win.
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

        // unit 0: colour source (InSampler)
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL20.glUniform1i(locInSampler, 0);
        // unit 1: depth (DepthSampler)
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
        GL20.glUniform1i(locDepthSamp, 1);
        // unit 2: blue-noise dither (NoiseSampler)
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, noiseTex);
        GL20.glUniform1i(locNoiseSamp, 2);

        GL20.glUniform2f(locPixelSize, 1.0f / fbW, 1.0f / fbH);
        GL20.glUniform1f(locFocusDist, focusDist);
        GL20.glUniform1f(locMaxBlurPx, maxBlurPx);
        GL20.glUniform1f(locNear, NEAR);
        GL20.glUniform1f(locFar,  currentDepthFar);
        GL20.glUniform1f(locFocalLen, focalLenMm);
        GL20.glUniform1f(locAperture, aperture);
        GL20.glUniform1f(locPxPerMm, fbH / 24.0f);  // 24mm sensor height maps to fbH px
        GL20.glUniform1f(locDofScale, dofScaleMm);

        // ---- Scissor region (viewfinder + bleed), applied to BOTH passes so the heavy
        // 2-D gather only runs where it matters ----
        double scale = mc.getWindow().getScaleFactor();
        int scX = (int)(fx  * scale);
        int scY = fbH - (int)(fy2 * scale);
        int scW = (int)((fx2 - fx) * scale);
        int scH = (int)((fy2 - fy) * scale);
        int bleed = (int) maxBlurPx;
        int expX = Math.max(0, scX - bleed);
        int expY = Math.max(0, scY - bleed);
        int expW = Math.min(fbW - expX, scW + 2 * bleed);
        int expH = Math.min(fbH - expY, scH + 2 * bleed);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(expX, expY, expW, expH);

        // ---- Pass 1: 2-D disc gather, main → aux (BlurDir.x = 1 → gather) ----
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, auxFbo);
        GL11.glViewport(0, 0, fbW, fbH);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, mainTex);
        GL20.glUniform2f(locBlurDir, 1.0f, 0.0f);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        // ---- Pass 2: copy aux → main (BlurDir.x = 0 → copy) ----
        //? if >=1.21.11 {
        /*// Write straight back into the scene colour texture via a dedicated FBO; mainTex is
        // detached immediately after the draw (leaving it attached while ScreenshotRecorder
        // reads the same texture crashes the NVIDIA driver).
        if (writeBackFbo == -1) writeBackFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, writeBackFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, mainTex, 0);
        *///?} else {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        //?}
        GL11.glViewport(0, 0, fbW, fbH);
        GL11.glScissor(expX, expY, expW, expH);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, auxTex);
        GL20.glUniform2f(locBlurDir, 0.0f, 0.0f);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        //? if >=1.21.11 {
        /*GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, 0, 0);
        *///?}

        // ---- Restore GL state ----
        if (!scissorWasEnabled) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(prevScissorBox[0], prevScissorBox[1], prevScissorBox[2], prevScissorBox[3]);
        GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        if (depthWasEnabled) GL11.glEnable(GL11.GL_DEPTH_TEST);
        if (blendWasEnabled) GL11.glEnable(GL11.GL_BLEND);
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
        GL20.glUseProgram(prevProgram);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
    }

    /**
     * Focus-peaking overlay: highlights in-focus-band, high-contrast edges in a warm colour,
     * the same manual-focus aid a real mirrorless body draws. Two passes through the shared
     * aux FBO (main -> aux detect+highlight, aux -> main copy-back), mirroring renderBlur()'s
     * ping-pong. Caller (applyScheduledPeaking) guarantees this never runs on a capture frame.
     */
    public static void applyPeaking(int fx, int fy, int fx2, int fy2, float focusDist) {
        if (depthTex == -1) return; // depth not captured yet

        MinecraftClient mc = MinecraftClient.getInstance();
        Framebuffer mainFb = mc.getFramebuffer();
        //? if >=1.21.11 {
        /*com.mojang.blaze3d.textures.GpuTexture gpuTex = mainFb.getColorAttachment();
        if (gpuTex == null) return;
        if (!(gpuTex instanceof net.minecraft.client.texture.GlTexture)) return;
        int mainTex = ((net.minecraft.client.texture.GlTexture) gpuTex).getGlId();
        *///?} else {
        int mainTex = mainFb.getColorAttachment();
        //?}
        if (mainTex == 0) return;

        int fbW = mainFb.textureWidth;
        int fbH = mainFb.textureHeight;
        if (fbW <= 0 || fbH <= 0) return;

        ensureInit(fbW, fbH);       // aux FBO / VAO (shared with DoF gather)
        if (peakProgram == -1) initPeakProgram();
        if (peakProgram == -1) return;

        // ---- Save GL state ----
        int prevProgram  = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevFbo      = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevVao      = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevActiveTU = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int prevTex0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        //? if >=1.21.11 {
        /*int prevSampler0 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL33.glBindSampler(0, 0);
        *///?}
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        int prevTex1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        //? if >=1.21.11 {
        /*int prevSampler1 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL33.glBindSampler(1, 0);
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

        GL20.glUseProgram(peakProgram);
        GL30.glBindVertexArray(vao);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL20.glUniform1i(peakLocIn, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
        GL20.glUniform1i(peakLocDepth, 1);

        GL20.glUniform2f(peakLocPixel, 1.0f / fbW, 1.0f / fbH);
        GL20.glUniform1f(peakLocFocus, focusDist);
        GL20.glUniform1f(peakLocNear, NEAR);
        GL20.glUniform1f(peakLocFar,  currentDepthFar);
        GL20.glUniform3f(peakLocColor, PEAK_COLOR[0], PEAK_COLOR[1], PEAK_COLOR[2]);

        double scale = mc.getWindow().getScaleFactor();
        int scX = (int)(fx  * scale);
        int scY = fbH - (int)(fy2 * scale);
        int scW = (int)((fx2 - fx) * scale);
        int scH = (int)((fy2 - fy) * scale);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(scX, scY, scW, scH);

        // ---- Pass 0: detect + highlight, main -> aux ----
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, auxFbo);
        GL11.glViewport(0, 0, fbW, fbH);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, mainTex);
        GL20.glUniform1i(peakLocPass, 0);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        // ---- Pass 1: copy aux -> main ----
        //? if >=1.21.11 {
        /*if (writeBackFbo == -1) writeBackFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, writeBackFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, mainTex, 0);
        *///?} else {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        //?}
        GL11.glViewport(0, 0, fbW, fbH);
        GL11.glScissor(scX, scY, scW, scH);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, auxTex);
        GL20.glUniform1i(peakLocPass, 1);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        //? if >=1.21.11 {
        /*GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, 0, 0);
        *///?}

        // ---- Restore GL state ----
        if (!scissorWasEnabled) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(prevScissorBox[0], prevScissorBox[1], prevScissorBox[2], prevScissorBox[3]);
        GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        if (depthWasEnabled) GL11.glEnable(GL11.GL_DEPTH_TEST);
        if (blendWasEnabled) GL11.glEnable(GL11.GL_BLEND);
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
        GL20.glUseProgram(prevProgram);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
    }

    /**
     * Live-viewfinder digital zoom: reconstructs the frame from a sample grid {@code blockPx}
     * destination-pixels apart, throwing away the detail a real sensor wouldn't have resolved
     * at this focal length (see {@link dev.hitom.photographica.component.LensKind#digitalZoomSoftenPx}).
     * The framing itself is untouched — the render already used the true focal length's FOV —
     * so this only ever costs sharpness, never magnification. Structurally identical to
     * {@link #applyPeaking}: single full-res pass, ping-ponged through aux because reading and
     * writing the same texture in one draw is a feedback loop.
     */
    public static void applyDigitalZoom(int fx, int fy, int fx2, int fy2, float blockPx) {
        if (blockPx <= 1.0f) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        Framebuffer mainFb = mc.getFramebuffer();
        //? if >=1.21.11 {
        /*com.mojang.blaze3d.textures.GpuTexture gpuTex = mainFb.getColorAttachment();
        if (gpuTex == null) return;
        if (!(gpuTex instanceof net.minecraft.client.texture.GlTexture)) return;
        int mainTex = ((net.minecraft.client.texture.GlTexture) gpuTex).getGlId();
        *///?} else {
        int mainTex = mainFb.getColorAttachment();
        //?}
        if (mainTex == 0) return;

        int fbW = mainFb.textureWidth;
        int fbH = mainFb.textureHeight;
        if (fbW <= 0 || fbH <= 0) return;

        ensureInit(fbW, fbH);       // aux FBO / VAO (shared with DoF gather/peaking)
        if (zoomProgram == -1) initZoomProgram();
        if (zoomProgram == -1) return;

        // ---- Save GL state ----
        int prevProgram  = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevFbo      = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevVao      = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevActiveTU = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int prevTex0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        //? if >=1.21.11 {
        /*int prevSampler0 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL33.glBindSampler(0, 0);
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

        GL20.glUseProgram(zoomProgram);
        GL30.glBindVertexArray(vao);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL20.glUniform1i(zoomLocIn, 0);
        GL20.glUniform2f(zoomLocPixel, 1.0f / fbW, 1.0f / fbH);
        GL20.glUniform1f(zoomLocBlock, blockPx);

        double scale = mc.getWindow().getScaleFactor();
        int scX = (int)(fx  * scale);
        int scY = fbH - (int)(fy2 * scale);
        int scW = (int)((fx2 - fx) * scale);
        int scH = (int)((fy2 - fy) * scale);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(scX, scY, scW, scH);

        // ---- Pass 0: soften, main -> aux ----
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, auxFbo);
        GL11.glViewport(0, 0, fbW, fbH);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, mainTex);
        GL20.glUniform1i(zoomLocPass, 0);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        // ---- Pass 1: copy aux -> main ----
        //? if >=1.21.11 {
        /*if (writeBackFbo == -1) writeBackFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, writeBackFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, mainTex, 0);
        *///?} else {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        //?}
        GL11.glViewport(0, 0, fbW, fbH);
        GL11.glScissor(scX, scY, scW, scH);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, auxTex);
        GL20.glUniform1i(zoomLocPass, 1);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        //? if >=1.21.11 {
        /*GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, 0, 0);
        *///?}

        // ---- Restore GL state ----
        if (!scissorWasEnabled) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(prevScissorBox[0], prevScissorBox[1], prevScissorBox[2], prevScissorBox[3]);
        GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        if (depthWasEnabled) GL11.glEnable(GL11.GL_DEPTH_TEST);
        if (blendWasEnabled) GL11.glEnable(GL11.GL_BLEND);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex0);
        //? if >=1.21.11 {
        /*GL33.glBindSampler(0, prevSampler0);
        *///?}
        GL13.glActiveTexture(prevActiveTU);
        GL30.glBindVertexArray(prevVao);
        GL20.glUseProgram(prevProgram);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
    }

    // -------------------------------------------------------------------------

    private static void ensureInit(int fbW, int fbH) {
        if (program == -1) initProgram();
        if (auxFbo == -1 || auxW != fbW || auxH != fbH) initAux(fbW, fbH);
        if (noiseTex == -1) initNoise();
    }

    /** Upload the bundled 64x64 blue-noise dither (raw single-channel bytes) as a GL_R8
     *  texture used to rotate each pixel's gather samples. */
    private static void initNoise() {
        byte[] data;
        try (java.io.InputStream is = EvfBlurRenderer.class.getResourceAsStream(
                "/assets/photographica/textures/evf_bluenoise.bin")) {
            if (is == null) { System.err.println("[Photographica] blue-noise texture missing"); return; }
            data = is.readAllBytes();
        } catch (Exception e) {
            System.err.println("[Photographica] blue-noise load failed: " + e);
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
            String vshSrc = readResource("/assets/photographica/shaders/evf_blur.vsh");
            String fshSrc = readResource("/assets/photographica/shaders/evf_blur.fsh");

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
                Photographica.LOGGER.error("EvfBlur link error: {}", GL20.glGetProgramInfoLog(prog));
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
            locMaxBlurPx = GL20.glGetUniformLocation(program, "MaxBlurPx");
            locNear      = GL20.glGetUniformLocation(program, "Near");
            locFar       = GL20.glGetUniformLocation(program, "Far");
            locFocalLen  = GL20.glGetUniformLocation(program, "FocalLenMm");
            locAperture  = GL20.glGetUniformLocation(program, "Aperture");
            locPxPerMm   = GL20.glGetUniformLocation(program, "PxPerMm");
            locDofScale  = GL20.glGetUniformLocation(program, "DofScale");

            // Full-screen quad (TRIANGLE_STRIP): bottom-left, bottom-right, top-left, top-right
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

            Photographica.LOGGER.info("EvfBlurRenderer initialised");
        } catch (Exception e) {
            Photographica.LOGGER.error("EvfBlurRenderer init failed", e);
        }
    }

    /** Compiles the focus-peaking program. Reuses evf_blur.vsh (plain passthrough) and the
     *  DoF program's VAO/VBO — peaking draws the same full-screen quad. */
    private static void initPeakProgram() {
        try {
            String vshSrc = readResource("/assets/photographica/shaders/evf_blur.vsh");
            String fshSrc = readResource("/assets/photographica/shaders/evf_peaking.fsh");

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
                Photographica.LOGGER.error("EvfPeaking link error: {}", GL20.glGetProgramInfoLog(prog));
                GL20.glDeleteProgram(prog);
                return;
            }

            peakProgram  = prog;
            peakLocIn    = GL20.glGetUniformLocation(peakProgram, "InSampler");
            peakLocDepth = GL20.glGetUniformLocation(peakProgram, "DepthSampler");
            peakLocPass  = GL20.glGetUniformLocation(peakProgram, "Pass");
            peakLocPixel = GL20.glGetUniformLocation(peakProgram, "PixelSize");
            peakLocFocus = GL20.glGetUniformLocation(peakProgram, "FocusDist");
            peakLocNear  = GL20.glGetUniformLocation(peakProgram, "Near");
            peakLocFar   = GL20.glGetUniformLocation(peakProgram, "Far");
            peakLocColor = GL20.glGetUniformLocation(peakProgram, "PeakColor");

            Photographica.LOGGER.info("EvfPeaking initialised");
        } catch (Exception e) {
            Photographica.LOGGER.error("EvfPeaking init failed", e);
        }
    }

    /** Compiles the drone digital-zoom program. Reuses evf_blur.vsh (plain passthrough) and
     *  the DoF program's VAO/VBO — same full-screen quad every one of these passes draws. */
    private static void initZoomProgram() {
        try {
            String vshSrc = readResource("/assets/photographica/shaders/evf_blur.vsh");
            String fshSrc = readResource("/assets/photographica/shaders/digital_zoom.fsh");

            int vs = compileShader(GL20.GL_VERTEX_SHADER,   "evf_blur.vsh (digital zoom)", vshSrc);
            int fs = compileShader(GL20.GL_FRAGMENT_SHADER, "digital_zoom.fsh", fshSrc);
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
                Photographica.LOGGER.error("DigitalZoom link error: {}", GL20.glGetProgramInfoLog(prog));
                GL20.glDeleteProgram(prog);
                return;
            }

            zoomProgram  = prog;
            zoomLocIn    = GL20.glGetUniformLocation(zoomProgram, "InSampler");
            zoomLocPass  = GL20.glGetUniformLocation(zoomProgram, "Pass");
            zoomLocPixel = GL20.glGetUniformLocation(zoomProgram, "PixelSize");
            zoomLocBlock = GL20.glGetUniformLocation(zoomProgram, "BlockPx");

            Photographica.LOGGER.info("DigitalZoom initialised");
        } catch (Exception e) {
            Photographica.LOGGER.error("DigitalZoom init failed", e);
        }
    }

    private static int compileShader(int type, String name, String src) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, src);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            Photographica.LOGGER.error("EvfBlur shader compile error [{}]: {}", name, GL20.glGetShaderInfoLog(id));
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
        Photographica.LOGGER.debug("EvfBlur aux FBO resized to {}x{}", w, h);
    }

    private static String readResource(String path) throws Exception {
        try (InputStream is = EvfBlurRenderer.class.getResourceAsStream(path)) {
            if (is == null) throw new RuntimeException("Resource not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static void close() {
        if (program     != -1) { GL20.glDeleteProgram(program);     program     = -1; }
        if (peakProgram != -1) { GL20.glDeleteProgram(peakProgram); peakProgram = -1; }
        if (zoomProgram != -1) { GL20.glDeleteProgram(zoomProgram); zoomProgram = -1; }
        if (vao      != -1) { GL30.glDeleteVertexArrays(vao);      vao      = -1; }
        if (vbo      != -1) { GL15.glDeleteBuffers(vbo);           vbo      = -1; }
        if (auxFbo   != -1) { GL30.glDeleteFramebuffers(auxFbo);   auxFbo   = -1; }
        if (auxTex   != -1) { GL11.glDeleteTextures(auxTex);       auxTex   = -1; }
        if (depthTex != -1) { GL11.glDeleteTextures(depthTex);     depthTex = -1; }
    }
}
