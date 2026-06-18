package dev.shunti.snapmatica.client;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL43;

import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

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

    private static int depthTex     = -1;
    static int depthTexW    = 0;
    static int depthTexH    = 0;
    private static int centerReadFbo = -1;

    private static int writeBackFbo = -1;

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

    /** mm of subject distance per block. 200 = miniature (strong bokeh, used to keep
     *  the still viewfinder preview matching the CPU photo). 1000 = realistic 1 block
     *  = 1 m, used for video so depth of field behaves like a real camera. */
    public static final float DOF_SCALE_STILL = 200.0f;
    public static final float DOF_SCALE_VIDEO = 1000.0f;

    private static final float NEAR = 0.05f;
    public static float currentDepthFar = 512.0f;
    private static final int GL_TEXTURE_COMPARE_MODE = 0x884C;

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

    // Scheduled blur parameters (set in HUD extractRenderState, applied after renderLevel).
    private static boolean blurScheduled      = false;
    private static float   scheduledFocusDist = 0f;
    private static float   scheduledAperture  = 0f;
    private static float   scheduledFocalLen  = 0f;
    private static int     scheduledFx = 0, scheduledFy = 0, scheduledFx2 = 0, scheduledFy2 = 0;

    /**
     * Store blur parameters from HUD extractRenderState (no raw GL allowed there).
     * Call applyScheduledBlur() after renderLevel() to actually execute.
     */
    public static void scheduleBlur(int fx, int fy, int fx2, int fy2,
                                    float focusDist, float aperture, float focalLenMm) {
        scheduledFx = fx; scheduledFy = fy;
        scheduledFx2 = fx2; scheduledFy2 = fy2;
        scheduledFocusDist = focusDist;
        scheduledAperture  = aperture;
        scheduledFocalLen  = focalLenMm;
        blurScheduled = true;
    }

    /**
     * Execute the blur scheduled by scheduleBlur(). Safe to call after renderLevel() returns.
     */
    public static void applyScheduledBlur() {
        if (!blurScheduled) return;
        blurScheduled = false;
        renderBlur(scheduledFx, scheduledFy, scheduledFx2, scheduledFy2,
                scheduledFocusDist, scheduledAperture, scheduledFocalLen, DOF_SCALE_STILL);
    }

    /** Copy depth buffer. Call during LevelRenderEvents.END_MAIN. */
    public static void captureDepth(int fbW, int fbH) {
        RenderTarget mainFb = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (mainFb == null) return;
        GpuTexture depthGpu = mainFb.getDepthTexture();
        if (!(depthGpu instanceof GlTexture glDepth)) return;
        int srcDepthId = glDepth.glId();
        if (srcDepthId <= 0) return;
        int fw = mainFb.width;
        int fh = mainFb.height;
        if (fw <= 0 || fh <= 0) return;

        int prevActiveTU = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int prevTex2D    = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        if (depthTex == -1 || depthTexW != fw || depthTexH != fh) {
            if (depthTex != -1) GL11.glDeleteTextures(depthTex);
            depthTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT32,
                    fw, fh, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_UNSIGNED_INT,
                    (java.nio.ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, 0);
            depthTexW = fw;
            depthTexH = fh;
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex2D);
        GL13.glActiveTexture(prevActiveTU);
        GL43.glCopyImageSubData(
                srcDepthId, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                depthTex,   GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                fw, fh, 1);
    }

    public static void renderBlur(int fx, int fy, int fx2, int fy2,
                                  float focusDist, float aperture, float focalLenMm,
                                  float dofScaleMm) {
        if (depthTex == -1) return;
        float maxBlurPx = Math.min(50.0f / aperture, 24.0f);
        if (maxBlurPx < 0.5f) return;

        Minecraft mc = Minecraft.getInstance();
        RenderTarget mainFb = mc.gameRenderer.mainRenderTarget();
        GpuTexture gpuTex = mainFb.getColorTexture();
        if (!(gpuTex instanceof GlTexture glTex)) return;
        int mainTex = glTex.glId();
        if (mainTex == 0) return;

        int fbW = mainFb.width;
        int fbH = mainFb.height;
        if (fbW <= 0 || fbH <= 0) return;

        ensureInit(fbW, fbH);
        if (program == -1) return;

        int prevProgram  = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevFbo      = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevVao      = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevActiveTU = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int prevTex0     = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevSampler0 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL33.glBindSampler(0, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        int prevTex1     = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevSampler1 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL33.glBindSampler(1, 0);
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

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL20.glUniform1i(locInSampler, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
        GL20.glUniform1i(locDepthSamp, 1);

        GL20.glUniform2f(locPixelSize, 1.0f / fbW, 1.0f / fbH);
        GL20.glUniform1f(locFocusDist, focusDist);
        GL20.glUniform1f(locMaxBlurPx, maxBlurPx);
        GL20.glUniform1f(locNear, NEAR);
        GL20.glUniform1f(locFar, currentDepthFar);
        GL20.glUniform1f(locFocalLen, focalLenMm);
        GL20.glUniform1f(locAperture, aperture);
        GL20.glUniform1f(locPxPerMm, fbH / 24.0f);  // 24mm sensor height maps to fbH px
        GL20.glUniform1f(locDofScale, dofScaleMm);

        // Pass 1: horizontal blur, main → aux
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, auxFbo);
        GL11.glViewport(0, 0, fbW, fbH);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, mainTex);
        GL20.glUniform2f(locBlurDir, 1.0f, 0.0f);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        // Pass 2: vertical blur, aux → main (scissored to viewfinder region)
        double scale = mc.getWindow().getGuiScale();
        int scX = (int)(fx  * scale);
        int scY = fbH - (int)(fy2 * scale);
        int scW = (int)((fx2 - fx) * scale);
        int scH = (int)((fy2 - fy) * scale);

        if (writeBackFbo == -1) writeBackFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, writeBackFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, mainTex, 0);

        GL11.glViewport(0, 0, fbW, fbH);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(scX, scY, scW, scH);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, auxTex);
        GL20.glUniform2f(locBlurDir, 0.0f, 1.0f);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, 0, 0);

        if (!scissorWasEnabled) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(prevScissorBox[0], prevScissorBox[1], prevScissorBox[2], prevScissorBox[3]);
        GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        if (depthWasEnabled)  GL11.glEnable(GL11.GL_DEPTH_TEST);
        if (blendWasEnabled)  GL11.glEnable(GL11.GL_BLEND);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex1);
        GL33.glBindSampler(1, prevSampler1);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex0);
        GL33.glBindSampler(0, prevSampler0);
        GL13.glActiveTexture(prevActiveTU);
        GL30.glBindVertexArray(prevVao);
        GL20.glUseProgram(prevProgram);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
    }

    public static float[] readLinearDepthCpu() {
        if (depthTex == -1 || depthTexW <= 0 || depthTexH <= 0) return null;
        int fbW = depthTexW, fbH = depthTexH;
        java.nio.FloatBuffer buf = BufferUtils.createFloatBuffer(fbW * fbH);
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, buf);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        final float near = NEAR;
        final float far  = currentDepthFar;
        float[] linear = new float[fbW * fbH];
        for (int i = 0; i < linear.length; i++) {
            float d = buf.get(i);
            float ndc = 2.0f * d - 1.0f;
            linear[i] = 2.0f * near * far / (far + near - ndc * (far - near));
        }
        return linear;
    }

    private static void ensureInit(int fbW, int fbH) {
        if (program == -1) initProgram();
        if (auxFbo == -1 || auxW != fbW || auxH != fbH) initAux(fbW, fbH);
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

    /**
     * Read center-pixel linear depth in blocks from the captured depth texture.
     * Returns FOCUS_INFINITY for sky/far-plane, -1.0f if unavailable.
     * Call after captureDepth().
     */
    public static float readCenterLinearDepthBlocks() {
        if (depthTex == -1 || depthTexW <= 0 || depthTexH <= 0) return -1.0f;
        if (centerReadFbo == -1) centerReadFbo = GL30.glGenFramebuffers();

        int prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, centerReadFbo);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, depthTex, 0);

        FloatBuffer buf = BufferUtils.createFloatBuffer(1);
        GL11.glReadPixels(depthTexW / 2, depthTexH / 2, 1, 1,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, buf);
        float rawD = buf.get(0);

        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);

        if (rawD >= 0.999999f) return SnapmaticaClient.FOCUS_INFINITY;
        if (rawD < 0.001f) return -1.0f;
        return NEAR * currentDepthFar / (currentDepthFar - rawD * (currentDepthFar - NEAR));
    }

    private static String readResource(String path) throws Exception {
        try (InputStream is = EvfBlurRenderer.class.getResourceAsStream(path)) {
            if (is == null) throw new RuntimeException("Resource not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
