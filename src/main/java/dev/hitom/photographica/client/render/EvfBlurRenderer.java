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
    private static int locInSampler  = -1;
    private static int locDepthSamp  = -1;
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

    public static final float DOF_SCALE_STILL = 200.0f;   // 1 block = 20 cm (still viewfinder)
    public static final float DOF_SCALE_VIDEO = 1000.0f;  // 1 block = 1 m  (video, realistic)

    private static final float NEAR = 0.05f;
    public  static float currentDepthFar = 512.0f;

    // GL_TEXTURE_COMPARE_MODE = 0x884C, GL_NONE = 0  (OpenGL 1.4+)
    private static final int GL_TEXTURE_COMPARE_MODE = 0x884C;

    // EVF schedule→execute state (used by the 1.21.11 path). 1.21.11 routes HUD draws
    // through a deferred GuiRenderState that discards raw-GL framebuffer writes done during
    // HUD rendering, so the blur is instead scheduled from the HUD and executed right after
    // renderWorld() — writing the result straight back into the scene colour texture via a
    // dedicated FBO. Harmless/no-op on <1.21.11, which keeps drawing directly from the HUD.
    private static int writeBackFbo   = -1;
    private static int writeBackFbTex = 0;
    private static boolean blurScheduled = false;
    private static int   scheduledFx, scheduledFy, scheduledFx2, scheduledFy2;
    private static float scheduledFocusDist, scheduledAperture, scheduledFocalLen;

    /** Records EVF blur params to be executed after renderWorld() (1.21.11 schedule→execute). */
    public static void scheduleBlur(int fx, int fy, int fx2, int fy2,
                                    float focusDist, float aperture, float focalLenMm) {
        scheduledFx = fx; scheduledFy = fy; scheduledFx2 = fx2; scheduledFy2 = fy2;
        scheduledFocusDist = focusDist; scheduledAperture = aperture; scheduledFocalLen = focalLenMm;
        blurScheduled = true;
    }

    /** Executes a scheduled blur; no-op if nothing is scheduled. Call after renderWorld(). */
    public static void applyScheduledBlur() {
        if (!blurScheduled) return;
        blurScheduled = false;
        renderBlur(scheduledFx, scheduledFy, scheduledFx2, scheduledFy2,
                scheduledFocusDist, scheduledAperture, scheduledFocalLen, DOF_SCALE_STILL);
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
        float maxBlurPx = Math.min(50.0f / aperture, 24.0f);
        if (maxBlurPx < 0.5f) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        Framebuffer mainFb = mc.getFramebuffer();
        //? if >=1.21.11 {
        /*com.mojang.blaze3d.textures.GpuTexture gpuTex = mainFb.getColorAttachment();
        if (gpuTex == null) return;
        int mainTex = ((net.minecraft.client.texture.GlTexture) gpuTex).getGlId();
        *///?} else {
        int mainTex = mainFb.getColorAttachment();
        //?}
        if (mainTex == 0) return;

        int fbW = mainFb.textureWidth;
        int fbH = mainFb.textureHeight;
        if (fbW <= 0 || fbH <= 0) return;

        ensureInit(fbW, fbH);
        if (program == -1) return;

        // ---- Save GL state ----
        int prevProgram  = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevFbo      = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevVao      = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevActiveTU = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int prevTex0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        int prevTex1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
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

        GL20.glUniform2f(locPixelSize, 1.0f / fbW, 1.0f / fbH);
        GL20.glUniform1f(locFocusDist, focusDist);
        GL20.glUniform1f(locMaxBlurPx, maxBlurPx);
        GL20.glUniform1f(locNear, NEAR);
        GL20.glUniform1f(locFar,  currentDepthFar);
        GL20.glUniform1f(locFocalLen, focalLenMm);
        GL20.glUniform1f(locAperture, aperture);
        GL20.glUniform1f(locPxPerMm, fbH / 24.0f);  // 24mm sensor height maps to fbH px
        GL20.glUniform1f(locDofScale, dofScaleMm);

        // ---- Pass 1: Horizontal blur, main → aux ----
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, auxFbo);
        GL11.glViewport(0, 0, fbW, fbH);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, mainTex);
        GL20.glUniform2f(locBlurDir, 1.0f, 0.0f);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        // ---- Pass 2: Vertical blur, aux → main ----
        //? if >=1.21.11 {
        /*// Write the full-frame blur straight back into the scene colour texture via a
        // dedicated FBO. The HUD bezels drawn later mask everything outside the viewfinder
        // frame, so no scissor is needed; a raw-GL write to the HUD-time framebuffer would
        // be discarded by 1.21.11's deferred GuiRenderState.
        if (writeBackFbo == -1 || writeBackFbTex != mainTex) {
            if (writeBackFbo != -1) GL30.glDeleteFramebuffers(writeBackFbo);
            writeBackFbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, writeBackFbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, mainTex, 0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            writeBackFbTex = mainTex;
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, writeBackFbo);
        GL11.glViewport(0, 0, fbW, fbH);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, auxTex);
        GL20.glUniform2f(locBlurDir, 0.0f, 1.0f);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        *///?} else {
        double scale = mc.getWindow().getScaleFactor();
        int scX = (int)(fx  * scale);
        int scY = fbH - (int)(fy2 * scale);
        int scW = (int)((fx2 - fx) * scale);
        int scH = (int)((fy2 - fy) * scale);

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        GL11.glViewport(0, 0, fbW, fbH);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(scX, scY, scW, scH);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, auxTex);
        GL20.glUniform2f(locBlurDir, 0.0f, 1.0f);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        //?}

        // ---- Restore GL state ----
        if (!scissorWasEnabled) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(prevScissorBox[0], prevScissorBox[1], prevScissorBox[2], prevScissorBox[3]);
        GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        if (depthWasEnabled) GL11.glEnable(GL11.GL_DEPTH_TEST);
        if (blendWasEnabled) GL11.glEnable(GL11.GL_BLEND);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex1);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex0);
        GL13.glActiveTexture(prevActiveTU);
        GL30.glBindVertexArray(prevVao);
        GL20.glUseProgram(prevProgram);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
    }

    // -------------------------------------------------------------------------

    private static void ensureInit(int fbW, int fbH) {
        if (program == -1) initProgram();
        if (auxFbo == -1 || auxW != fbW || auxH != fbH) initAux(fbW, fbH);
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
        if (program  != -1) { GL20.glDeleteProgram(program);       program  = -1; }
        if (vao      != -1) { GL30.glDeleteVertexArrays(vao);      vao      = -1; }
        if (vbo      != -1) { GL15.glDeleteBuffers(vbo);           vbo      = -1; }
        if (auxFbo   != -1) { GL30.glDeleteFramebuffers(auxFbo);   auxFbo   = -1; }
        if (auxTex   != -1) { GL11.glDeleteTextures(auxTex);       auxTex   = -1; }
        if (depthTex != -1) { GL11.glDeleteTextures(depthTex);     depthTex = -1; }
    }
}
