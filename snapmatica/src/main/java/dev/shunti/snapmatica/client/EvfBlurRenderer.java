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
import org.lwjgl.opengl.GL43;*/
//?}

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

    private static int depthTex  = -1;
    private static int depthTexW = 0;
    private static int depthTexH = 0;

    //? if >=1.21.11 {
    /*private static int writeBackFbo = -1;*/
    //?}

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
    private static final int GL_TEXTURE_COMPARE_MODE = 0x884C;

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
                fw_, fh_, 1);*/
        //?} else {
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
     * maxBlurPx is aperture-only — distScale removed so near objects blur
     * correctly even when focused far away.
     */
    public static void renderBlur(int fx, int fy, int fx2, int fy2,
                                  float focusDist, float aperture) {
        if (depthTex == -1) return;
        float maxBlurPx = Math.min(80.0f / (aperture * aperture), 32.0f);
        if (maxBlurPx < 0.5f) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        Framebuffer mainFb = mc.getFramebuffer();
        //? if >=1.21.11 {
        /*com.mojang.blaze3d.textures.GpuTexture gpuTex = mainFb.getColorAttachment();
        if (!(gpuTex instanceof net.minecraft.client.texture.GlTexture glTex)) return;
        int mainTex = glTex.getGlId();*/
        //?} else {
        int mainTex = mainFb.getColorAttachment();
        //?}
        if (mainTex == 0) return;

        int fbW = mainFb.textureWidth;
        int fbH = mainFb.textureHeight;
        if (fbW <= 0 || fbH <= 0) return;

        ensureInit(fbW, fbH);
        if (program == -1) return;

        int prevProgram  = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevFbo      = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int prevVao      = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevActiveTU = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int prevTex0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        //? if >=1.21.11 {
        /*// 1.21.11 binds sampler objects per texture unit (GlCommandEncoder.glBindSampler)
        // that persist after MC's draws. Our shader would sample through those instead of
        // the texture's own parameters, reading garbage. Unbind so our glTexParameteri wins.
        int prevSampler0 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL33.glBindSampler(0, 0);*/
        //?}
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        int prevTex1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        //? if >=1.21.11 {
        /*int prevSampler1 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL33.glBindSampler(1, 0);*/
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

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL20.glUniform1i(locInSampler, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
        GL20.glUniform1i(locDepthSamp, 1);

        GL20.glUniform2f(locPixelSize, 1.0f / fbW, 1.0f / fbH);
        GL20.glUniform1f(locFocusDist, focusDist);
        GL20.glUniform1f(locMaxBlurPx, maxBlurPx);
        GL20.glUniform1f(locNear, NEAR);
        GL20.glUniform1f(locFar,  FAR);

        // Pass 1: Horizontal blur, main → aux
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, auxFbo);
        GL11.glViewport(0, 0, fbW, fbH);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, mainTex);
        GL20.glUniform2f(locBlurDir, 1.0f, 0.0f);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        // Pass 2: Vertical blur, aux → main (scissored to viewfinder region)
        double scale = mc.getWindow().getScaleFactor();
        int scX = (int)(fx  * scale);
        int scY = fbH - (int)(fy2 * scale);
        int scW = (int)((fx2 - fx) * scale);
        int scH = (int)((fy2 - fy) * scale);

        //? if >=1.21.11 {
        /*if (writeBackFbo == -1) writeBackFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, writeBackFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, mainTex, 0);*/
        //?} else {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        //?}
        GL11.glViewport(0, 0, fbW, fbH);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(scX, scY, scW, scH);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, auxTex);
        GL20.glUniform2f(locBlurDir, 0.0f, 1.0f);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        //? if >=1.21.11 {
        /*GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, 0, 0);*/
        //?}

        // Restore GL state
        if (!scissorWasEnabled) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(prevScissorBox[0], prevScissorBox[1], prevScissorBox[2], prevScissorBox[3]);
        GL11.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        if (depthWasEnabled) GL11.glEnable(GL11.GL_DEPTH_TEST);
        if (blendWasEnabled) GL11.glEnable(GL11.GL_BLEND);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex1);
        //? if >=1.21.11 {
        /*GL33.glBindSampler(1, prevSampler1);*/
        //?}
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex0);
        //? if >=1.21.11 {
        /*GL33.glBindSampler(0, prevSampler0);*/
        //?}
        GL13.glActiveTexture(prevActiveTU);
        GL30.glBindVertexArray(prevVao);
        GL20.glUseProgram(prevProgram);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
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
