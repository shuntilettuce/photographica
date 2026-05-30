package dev.hitom.photographica.client.render;

import dev.hitom.photographica.Photographica;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.pipeline.RenderTarget;
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

/**
 * Two-pass separable Gaussian blur with per-pixel depth-of-field.
 *
 * Each pixel's blur radius is derived from its own depth vs the current
 * focus distance, so the subject stays sharp while the background blurs.
 *
 * captureDepth() must be called once per frame (during WorldRenderEvents.END_MAIN,
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

    // FBOs used for glBlitFramebuffer-based depth copy (more compatible than glCopyImageSubData)
    private static int blitReadFbo = -1;
    private static int blitDrawFbo = -1;

    // 1.21.11: dedicated write-back FBO targeting colorAttachment
    private static int writeBackFbo = -1;

    // Uniform locations
    private static int locInSampler  = -1;
    private static int locDepthSamp  = -1;
    private static int locBlurDir    = -1;
    private static int locPixelSize  = -1;
    private static int locFocusDist  = -1;
    private static int locMaxBlurPx  = -1;
    private static int locNear       = -1;
    private static int locFar        = -1;

    private static final float NEAR = 0.05f;
    private static final float FAR  = 512.0f;

    // GL_TEXTURE_COMPARE_MODE = 0x884C, GL_NONE = 0  (OpenGL 1.4+)
    private static final int GL_TEXTURE_COMPARE_MODE = 0x884C;

    /**
     * Copies the scene depth buffer into our own GPU texture so the EVF blur shader
     * can sample it during HUD rendering (after MC clears the main depth).
     * Uses glBlitFramebuffer which is more compatible than glCopyImageSubData.
     */
    public static void captureDepth(int fbW, int fbH) {
        RenderTarget mainFb = Minecraft.getInstance().getMainRenderTarget();
        if (mainFb == null) return;
        com.mojang.blaze3d.textures.GpuTexture depthGpu = mainFb.getDepthTexture();
        if (!(depthGpu instanceof com.mojang.blaze3d.opengl.GlTexture glDepth)) {
            Photographica.LOGGER.error("[Photographica] captureDepth: depth is not GlTexture ({})",
                    depthGpu == null ? "null" : depthGpu.getClass().getName());
            return;
        }
        int srcId = glDepth.glId();
        if (srcId <= 0) return;
        int fw = mainFb.width;
        int fh = mainFb.height;
        if (fw <= 0 || fh <= 0) return;

        // Allocate / reallocate our copy texture.
        int prevActiveTU = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int prevTex2D = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        if (depthTex == -1 || depthTexW != fw || depthTexH != fh) {
            if (depthTex != -1) GL11.glDeleteTextures(depthTex);
            depthTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT32,
                    fw, fh, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT,
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

        // Save FBO bindings.
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);

        // Attach MC's depth texture to a read FBO.
        if (blitReadFbo == -1) blitReadFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, blitReadFbo);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, srcId, 0);

        // Attach our copy texture to a draw FBO.
        if (blitDrawFbo == -1) blitDrawFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, blitDrawFbo);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, depthTex, 0);

        int rs = GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER);
        int ds = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
        if (rs == GL30.GL_FRAMEBUFFER_COMPLETE && ds == GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL30.glBlitFramebuffer(0, 0, fw, fh, 0, 0, fw, fh,
                    GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        } else {
            Photographica.LOGGER.error("[Photographica] captureDepth: FBO incomplete read={} draw={}", rs, ds);
        }

        // Restore.
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
    }

    /**
     * Applies depth-aware two-pass Gaussian blur to the viewfinder area.
     * Each pixel's blur radius is derived from its depth vs focusDist,
     * so in-focus pixels stay sharp while out-of-focus pixels blur.
     *
     * fx/fy/fx2/fy2 are in scaled GUI coordinates.
     */
    public static void renderBlur(int fx, int fy, int fx2, int fy2,
                                  float focusDist, float aperture) {
        if (depthTex == -1) return; // depth not captured yet
        float maxBlurPx = Math.min(80.0f / (aperture * aperture), 32.0f);
        if (maxBlurPx < 0.5f) return;

        Minecraft mc = Minecraft.getInstance();
        RenderTarget mainFb = mc.getMainRenderTarget();
        com.mojang.blaze3d.textures.GpuTexture gpuTex = mainFb.getColorTexture();
        if (!(gpuTex instanceof com.mojang.blaze3d.opengl.GlTexture glTex)) return;
        int mainTex = glTex.glId();
        if (mainTex == 0) return;

        int fbW = mainFb.width;
        int fbH = mainFb.height;
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
        // 1.21.11 binds sampler objects per texture unit (GlCommandEncoder.glBindSampler)
        // that persist after MC's draws. Our shader would sample through those instead of
        // the texture's own parameters, reading garbage. Unbind so our glTexParameteri wins.
        int prevSampler0 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL33.glBindSampler(0, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        int prevTex1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
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
        GL20.glUniform1f(locFar,  FAR);

        // ---- Pass 1: Horizontal blur, main → aux ----
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, auxFbo);
        GL11.glViewport(0, 0, fbW, fbH);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, mainTex);
        GL20.glUniform2f(locBlurDir, 1.0f, 0.0f);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        // ---- Pass 2: Vertical blur, aux → main (scissored to viewfinder) ----
        double scale = mc.getWindow().getGuiScale();
        int scX = (int)(fx  * scale);
        int scY = fbH - (int)(fy2 * scale);
        int scW = (int)((fx2 - fx) * scale);
        int scH = (int)((fy2 - fy) * scale);

        // In 1.21.11, HUD uses GuiRenderState extracted then drawn by GuiRenderer.
        // prevFbo (FBO 0 or transient) gets overwritten by presentTexture().
        // Write pass-2 output directly into colorAttachment so GuiRenderer sees it.
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

        // Detach mainTex from write-back FBO so the texture stays clean for Minecraft.
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, 0, 0);

        // ---- Restore GL state ----
        if (!scissorWasEnabled) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(prevScissorBox[0], prevScissorBox[1], prevScissorBox[2], prevScissorBox[3]);
        GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        if (depthWasEnabled) GL11.glEnable(GL11.GL_DEPTH_TEST);
        if (blendWasEnabled) GL11.glEnable(GL11.GL_BLEND);
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

    /**
     * Reads the scene depth directly from MC's main framebuffer depth texture to CPU.
     * Uses a temporary read FBO + glReadPixels (more reliable than glGetTexImage on a copy).
     * Returns null if depth is unavailable. GPU→CPU stall — call once per capture only.
     */
    public static float[] readLinearDepthCpu(int fbW, int fbH) {
        RenderTarget mainFb = Minecraft.getInstance().getMainRenderTarget();
        if (mainFb == null) return null;
        com.mojang.blaze3d.textures.GpuTexture depthGpu = mainFb.getDepthTexture();
        if (!(depthGpu instanceof com.mojang.blaze3d.opengl.GlTexture glDepth)) return null;
        int depthId = glDepth.glId();
        if (depthId <= 0) return null;

        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);

        // Attach MC's depth texture to a temporary read FBO.
        int readFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL11.GL_TEXTURE_2D, depthId, 0);

        FloatBuffer buf = null;
        int status = GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER);
        if (status == GL30.GL_FRAMEBUFFER_COMPLETE) {
            buf = BufferUtils.createFloatBuffer(fbW * fbH);
            GL11.glReadPixels(0, 0, fbW, fbH, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, buf);
        } else {
            Photographica.LOGGER.error("[Photographica] readLinearDepthCpu: FBO incomplete {}", status);
        }

        GL30.glDeleteFramebuffers(readFbo);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);

        if (buf == null) return null;

        final float near = 0.05f;
        final float far  = 512.0f;
        float[] linear = new float[fbW * fbH];
        for (int i = 0; i < linear.length; i++) {
            float d   = buf.get(i);
            float ndc = 2.0f * d - 1.0f;
            linear[i] = 2.0f * near * far / (far + near - ndc * (far - near));
        }
        return linear;
    }

    public static void close() {
        if (program     != -1) { GL20.glDeleteProgram(program);          program     = -1; }
        if (vao         != -1) { GL30.glDeleteVertexArrays(vao);         vao         = -1; }
        if (vbo         != -1) { GL15.glDeleteBuffers(vbo);              vbo         = -1; }
        if (auxFbo      != -1) { GL30.glDeleteFramebuffers(auxFbo);      auxFbo      = -1; }
        if (auxTex      != -1) { GL11.glDeleteTextures(auxTex);          auxTex      = -1; }
        if (depthTex    != -1) { GL11.glDeleteTextures(depthTex);        depthTex    = -1; }
        if (blitReadFbo  != -1) { GL30.glDeleteFramebuffers(blitReadFbo);   blitReadFbo  = -1; }
        if (blitDrawFbo  != -1) { GL30.glDeleteFramebuffers(blitDrawFbo);   blitDrawFbo  = -1; }
        if (writeBackFbo != -1) { GL30.glDeleteFramebuffers(writeBackFbo); writeBackFbo = -1; }
    }
}
