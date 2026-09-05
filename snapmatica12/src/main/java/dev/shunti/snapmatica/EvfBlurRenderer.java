package dev.shunti.snapmatica;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.Charset;

/**
 * The thin-lens defocus, on LWJGL 2.
 *
 * <p>Straight port of the 1.21.x renderer's GL orchestration: capture the scene's depth into a
 * texture of our own, run the disc gather into an aux target, then composite back over the
 * frame. The shader is byte-for-byte the same file — every optical decision lives there, so
 * nothing about the physics is re-derived here.
 *
 * <p>Three things differ from the modern versions and each is a reason this file exists rather
 * than the port being a matter of imports:
 * <ul>
 *   <li>LWJGL 2 spells several of these calls differently — the info-log getters take an
 *       explicit length, and there is no {@code glGetInteger} for every enum.</li>
 *   <li>1.12.2 gives its framebuffer a depth RENDERBUFFER, not a texture, so the depth cannot
 *       be sampled where it lies. It is copied out with glCopyTexImage2D, which reads whatever
 *       depth attachment the bound framebuffer has, renderbuffer or not.</li>
 *   <li>The near and far planes are not exposed. They are recovered from the projection matrix
 *       while the world's projection is still current, which is exact and survives anything a
 *       mod does to the render distance.</li>
 * </ul>
 */
public final class EvfBlurRenderer {
    private EvfBlurRenderer() {}

    private static int program = -1;
    private static int vao = -1, vbo = -1;
    private static int auxFbo = -1, auxTex = -1, auxW = 0, auxH = 0;
    private static int depthTex = -1, depthW = 0, depthH = 0;
    private static int srcTex = -1, srcW = 0, srcH = 0;
    private static int noiseTex = -1;

    private static boolean initFailed = false;
    public static String lastError = null;

    /** Framebuffer the last pass ran over, so the capture reads back the same rectangle. */
    public static int lastWidth = 0, lastHeight = 0;

    /**
     * Per-frame diagnostics, printed only while {@link #trace} is on.
     *
     * <p>Turned on rather than left in because the interesting fault is an alternation between
     * two states, and an alternation is invisible in a single sample. Everything that could
     * plausibly differ frame to frame is printed together, so a flicker shows up as a column
     * that changes on every other line rather than as something to reason about.
     */
    public static boolean trace = false;
    private static String lastTraceLine = null;
    /** Frame the depth was last taken on, so the colour is never blurred against another's. */
    private static long depthFrame = -1;
    private static long frameNo = 0;

    /** Near/far clip in blocks, recovered from the projection matrix each frame. */
    public static float near = 0.05f, far = 512.0f;

    private static final FloatBuffer MAT = BufferUtils.createFloatBuffer(16);
    private static final IntBuffer VP = BufferUtils.createIntBuffer(16);
    private static final IntBuffer DRAWBUF = BufferUtils.createIntBuffer(16);

    // ── uniforms ────────────────────────────────────────────────────────────────────────
    private static int uIn, uDepth, uNoise, uNearS, uBgS, uPass, uBlurDir, uPixelSize;
    private static int uFocusDist, uAfMode, uNearDownscale, uNearLayer, uMaxBlurPx;
    private static int uNear, uFar, uFocalLen, uAperture, uPxPerMm, uDofScale;
    private static int uDistortK, uAspect, uDoGather, uMotionRot, uMotionVel, uFocalPx;

    /**
     * Reads the near and far planes back out of the live projection matrix.
     *
     * <p>Must be called while the world's projection is bound — the HUD replaces it with an
     * orthographic one, whose corresponding terms mean something else entirely.
     */
    /**
     * Log every frame whose state DIFFERS from the one before it, until switched off.
     *
     * <p>Printing a fixed number of frames was the wrong instrument: it produced a thousand
     * identical lines and, because the fault happens every few seconds, a window that did not
     * contain one. A fault that is rare and a log that is uniform are the same problem read
     * from two ends. Logging only changes turns the whole session into the window and leaves
     * exactly the anomalies in it.
     */
    public static void toggleTrace() {
        trace = !trace;
        lastTraceLine = null;
        System.out.println("[Snapmatica] trace " + (trace ? "on (logging changes only)" : "off"));
    }

    public static void captureProjection() {
        MAT.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, MAT);
        float m10 = MAT.get(10), m14 = MAT.get(14);
        float d = m10 - 1.0f;
        if (Math.abs(d) > 1e-6f && Math.abs(m10 + 1.0f) > 1e-6f) {
            float n = m14 / d;
            float f = m14 / (m10 + 1.0f);
            if (n > 0.0f && f > n && f < 1.0e7f) { near = n; far = f; }
        }
    }

    /** Copies the bound framebuffer's depth attachment into a texture we can sample. */
    public static void captureDepth() {
        frameNo++;
        VP.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, VP);
        int w = VP.get(2), h = VP.get(3);
        if (w <= 0 || h <= 0) return;

        if (depthTex == -1) {
            depthTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            depthW = 0; depthH = 0;
        }
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
        if (w != depthW || h != depthH) {
            GL11.glCopyTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, 0, 0, w, h, 0);
            depthW = w; depthH = h;
        } else {
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        captureProjection();
        depthFrame = frameNo;
    }

    // ── the pass ────────────────────────────────────────────────────────────────────────

    /**
     * Largest circle of confusion the current optics can produce, in framebuffer pixels — the
     * same function the 1.21.x renderer uses, so the two agree on when there is anything to do.
     */
    public static float maxCocPx(float focusDist, float aperture, float focalLenMm,
                                 float dofScaleMm, float pxPerMm) {
        return Math.max(cocPxAt(0.3f, focusDist, aperture, focalLenMm, dofScaleMm, pxPerMm),
                        cocPxAt(Math.max(far, 64.0f), focusDist, aperture, focalLenMm,
                                dofScaleMm, pxPerMm));
    }

    private static float cocPxAt(float depthBlocks, float focusDist, float aperture,
                                 float focalLenMm, float dofScaleMm, float pxPerMm) {
        float depthM = Math.max(depthBlocks, 0.05f);
        float cocMM;
        if (focusDist >= 99999.0f) {
            cocMM = (focalLenMm * focalLenMm) / (aperture * depthM * dofScaleMm);
        } else {
            float s1mm = focusDist * dofScaleMm;
            float denom = aperture * Math.max(s1mm - focalLenMm, 1.0f);
            cocMM = (focalLenMm * focalLenMm) * Math.abs(depthM - focusDist) / (depthM * denom);
        }
        float airyMM = 2.44f * 0.00055f * aperture;
        return (float) Math.sqrt(cocMM * cocMM + airyMM * airyMM) * pxPerMm;
    }

    public static void renderBlur(float focusDist, float aperture, float focalLenMm,
                                  float dofScaleMm, boolean gpuAutoFocus) {
        if (initFailed || depthTex == -1) return;

        // Whatever is bound right now IS the frame.
        //
        // This used to read the bound framebuffer but write to Minecraft's colour attachment by
        // name, on the assumption that they are the same thing. Under OptiFine's shader
        // pipeline they are not: the world is rendered into buffers of OptiFine's own and
        // Minecraft's is not what the frame is being assembled in. Reading one and writing the
        // other meant our output landed somewhere that is never cleared, and came back as the
        // next frame's input — which is why the in-focus band, whose value is an identity copy
        // of the source, painted over itself for ever while the defocused parts, recomputed
        // from scratch every frame, looked fine. It appeared the moment OptiFine's pipeline was
        // switched on at all, internal programs included, and never on the vanilla path.
        //
        // Naming no framebuffer removes the assumption rather than patching it: read from what
        // is bound, write back to what is bound. That is also what makes this survive the next
        // renderer that inserts itself here.
        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        VP.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, VP);
        int fbW = VP.get(2), fbH = VP.get(3);
        if (fbW <= 0 || fbH <= 0) return;

        // Colour and depth must come from the same frame.
        //
        // The depth is taken at the end of the world pass and the colour here, and nothing
        // guarantees the first happened. When it did not, the defocus runs against whatever
        // the depth texture last held — or, on the frame it was allocated, against zeroes,
        // which read as the near clip plane and put the entire screen a hand's breadth from
        // the lens. That blurs the whole frame into a single colour, and a frame of flat sky
        // is what it looks like.
        if (depthFrame != frameNo) {
            if (trace) System.out.println("[Snapmatica] SKIPPED: no depth this frame ("
                    + depthFrame + " vs " + frameNo + ")");
            return;
        }
        lastWidth = fbW; lastHeight = fbH;
        if (!ensureInit(fbW, fbH)) return;
        snapshotColour(fbW, fbH);

        float pxPerMm = fbH / 24.0f;
        float maxBlurPx = Math.min(maxCocPx(focusDist, aperture, focalLenMm, dofScaleMm, pxPerMm),
                                   fbH * 0.75f);
        if (maxBlurPx < 1.0f) return;

        for (int e = 0; e < 32 && GL11.glGetError() != GL11.GL_NO_ERROR; e++) { /* drain */ }

        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevArray   = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int prevVao     = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevActive  = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        // Through GlStateManager, not raw GL.
        //
        // 1.12.2's GlStateManager keeps a shadow copy of the state it manages and skips any
        // call that it believes is already in effect. Changing that state underneath it with
        // raw glEnable/glDisable, or binding a texture without telling it, leaves the shadow
        // disagreeing with the driver — and the next thing to ask for a state it thinks is
        // already set gets nothing, which is how the HUD ends up flickering a frame at a time.
        // Anything GlStateManager owns has to be changed by asking it.
        boolean prevScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);

        GlStateManager.disableDepth();
        GlStateManager.disableBlend();
        GlStateManager.disableCull();
        GlStateManager.disableAlpha();
        GlStateManager.disableLighting();
        GlStateManager.disableFog();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        GL20.glUseProgram(program);
        GL30.glBindVertexArray(vao);

        GlStateManager.setActiveTexture(GL13.GL_TEXTURE1);
        GlStateManager.bindTexture(depthTex);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE2);
        GlStateManager.bindTexture(noiseTex);

        GL20.glUniform1i(uIn, 0);
        GL20.glUniform1i(uDepth, 1);
        GL20.glUniform1i(uNoise, 2);
        GL20.glUniform1i(uNearS, 3);
        GL20.glUniform1i(uBgS, 4);
        GL20.glUniform1f(uFocusDist, focusDist);
        GL20.glUniform1i(uAfMode, gpuAutoFocus ? 1 : 0);
        GL20.glUniform1i(uNearDownscale, 2);
        GL20.glUniform1i(uNearLayer, 0);
        GL20.glUniform1f(uMaxBlurPx, maxBlurPx);
        GL20.glUniform1f(uNear, near);
        GL20.glUniform1f(uFar, far);
        GL20.glUniform1f(uFocalLen, focalLenMm);
        GL20.glUniform1f(uAperture, aperture);
        GL20.glUniform1f(uPxPerMm, pxPerMm);
        GL20.glUniform1f(uDofScale, dofScaleMm);
        GL20.glUniform1f(uDistortK, 0.0f);
        GL20.glUniform1i(uDoGather, 1);
        GL20.glUniform2f(uMotionRot, 0.0f, 0.0f);
        GL20.glUniform3f(uMotionVel, 0.0f, 0.0f, 0.0f);
        GL20.glUniform1f(uFocalPx, (fbH * 0.5f) / (12.0f / Math.max(focalLenMm, 1.0f)));
        GL20.glUniform1f(uAspect, (float) fbW / (float) fbH);
        GL20.glUniform2f(uPixelSize, 1.0f / fbW, 1.0f / fbH);
        GL20.glUniform1i(uPass, 0);

        GL11.glViewport(0, 0, fbW, fbH);

        // Gather: snapshot -> aux
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, auxFbo);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(srcTex);
        GL20.glUniform2f(uBlurDir, 1.0f, 0.0f);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        // Composite: aux -> back into the framebuffer we were called with.
        //
        // Its draw buffers belong to whoever bound it, and under OptiFine's shader pipeline
        // there are several of them: normals, and buffers a pack keeps between frames. This
        // shader has one output, so drawing with that set still in force writes into all of
        // them — undefined values into buffers we do not own, every frame. Restricted to the
        // first attachment for the duration of the draw and put back exactly as found.
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        int maxDraw = Math.min(GL11.glGetInteger(GL20.GL_MAX_DRAW_BUFFERS), 16);
        DRAWBUF.clear();
        for (int i = 0; i < maxDraw; i++) DRAWBUF.put(GL11.glGetInteger(GL20.GL_DRAW_BUFFER0 + i));
        DRAWBUF.flip();
        GL20.glDrawBuffers(prevFbo == 0 ? GL11.GL_BACK : GL30.GL_COLOR_ATTACHMENT0);
        GL11.glViewport(0, 0, fbW, fbH);
        GlStateManager.bindTexture(auxTex);
        GL20.glUniform2f(uBlurDir, 0.0f, 0.0f);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

        // Restore
        DRAWBUF.rewind();
        GL20.glDrawBuffers(DRAWBUF);
        GL30.glBindVertexArray(prevVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevArray);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        GL20.glUseProgram(prevProgram);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE1);
        GlStateManager.bindTexture(0);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE2);
        GlStateManager.bindTexture(0);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(0);
        GlStateManager.setActiveTexture(prevActive);
        // Left as the interface expects to find it, not as the world does.
        //
        // This pass runs inside overlay rendering: Minecraft has already switched to the
        // orthographic projection and cleared the depth buffer, and everything drawn from here
        // on is flat. Handing it back the world's state — depth testing on, texturing off,
        // whatever colour the last draw happened to leave — is what made the interface flicker,
        // and it is why nudging any control appeared to cure it: redrawing an extra element
        // set some of it back by accident. There is no way to read GlStateManager's cache, so
        // the honest thing is to leave it in the state this point in the frame actually wants.
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.enableAlpha();
        GlStateManager.disableCull();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        if (prevScissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);

        int err = GL11.glGetError();
        if (err != GL11.GL_NO_ERROR) lastError = "GL error 0x" + Integer.toHexString(err);
        if (trace) {
            String line = String.format(
                    "fbo=%d vp=%dx%d depth=%dx%d src=%dx%d aux=%dx%d "
                  + "maxBlur=%.1f near=%.3f far=%.1f focus=%.2f err=%s",
                    prevFbo, fbW, fbH, depthW, depthH, srcW, srcH, auxW, auxH,
                    maxBlurPx, near, far, focusDist,
                    err == GL11.GL_NO_ERROR ? "-" : Integer.toHexString(err));
            if (!line.equals(lastTraceLine)) {
                System.out.println("[Snapmatica] " + line);
                lastTraceLine = line;
            }
        }
    }

    // ── setup ───────────────────────────────────────────────────────────────────────────

    /** Copies the bound framebuffer's colour into a texture of ours, so the gather never
     *  samples the attachment the composite is about to write. */
    private static void snapshotColour(int w, int h) {
        if (srcTex == -1) {
            srcTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, srcTex);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            srcW = 0; srcH = 0;
        }
        int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, srcTex);
        if (w != srcW || h != srcH) {
            GL11.glCopyTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 0, 0, w, h, 0);
            srcW = w; srcH = h;
        } else {
            GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
    }

    private static boolean ensureInit(int w, int h) {
        if (program == -1) {
            if (!buildProgram()) { initFailed = true; return false; }
            buildQuad();
            buildNoise();
        }
        if (auxFbo == -1 || auxW != w || auxH != h) {
            if (auxTex != -1) GL11.glDeleteTextures(auxTex);
            if (auxFbo != -1) GL30.glDeleteFramebuffers(auxFbo);
            auxTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, auxTex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            auxFbo = GL30.glGenFramebuffers();
            int prev = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, auxFbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                    GL11.GL_TEXTURE_2D, auxTex, 0);
            int st = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prev);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            if (st != GL30.GL_FRAMEBUFFER_COMPLETE) {
                lastError = "aux framebuffer incomplete: 0x" + Integer.toHexString(st);
                initFailed = true;
                return false;
            }
            auxW = w; auxH = h;
        }
        return true;
    }

    private static boolean buildProgram() {
        String vs = readResource("/assets/snapmatica/shaders/evf_blur.vsh");
        String fs = readResource("/assets/snapmatica/shaders/evf_blur.fsh");
        if (vs == null || fs == null) { lastError = "shader resources missing"; return false; }

        int v = compile(GL20.GL_VERTEX_SHADER, vs, "vertex");
        if (v == 0) return false;
        int f = compile(GL20.GL_FRAGMENT_SHADER, fs, "fragment");
        if (f == 0) { GL20.glDeleteShader(v); return false; }

        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, v);
        GL20.glAttachShader(p, f);
        GL20.glBindAttribLocation(p, 0, "Position");
        GL20.glBindAttribLocation(p, 1, "UV0");
        GL20.glLinkProgram(p);
        GL20.glDeleteShader(v);
        GL20.glDeleteShader(f);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            lastError = "link failed: " + GL20.glGetProgramInfoLog(p, 4096);
            GL20.glDeleteProgram(p);
            return false;
        }
        program = p;

        uIn = loc("InSampler");        uDepth = loc("DepthSampler");  uNoise = loc("NoiseSampler");
        uNearS = loc("NearSampler");   uBgS = loc("BgSampler");       uPass = loc("Pass");
        uBlurDir = loc("BlurDir");     uPixelSize = loc("PixelSize"); uFocusDist = loc("FocusDist");
        uAfMode = loc("AfMode");       uNearDownscale = loc("NearDownscale");
        uNearLayer = loc("NearLayer"); uMaxBlurPx = loc("MaxBlurPx"); uNear = loc("Near");
        uFar = loc("Far");             uFocalLen = loc("FocalLenMm"); uAperture = loc("Aperture");
        uPxPerMm = loc("PxPerMm");     uDofScale = loc("DofScale");   uDistortK = loc("DistortK");
        uAspect = loc("Aspect");       uDoGather = loc("DoGather");   uMotionRot = loc("MotionRotPx");
        uMotionVel = loc("MotionVelCam"); uFocalPx = loc("FocalPx");
        return true;
    }

    private static int loc(String name) { return GL20.glGetUniformLocation(program, name); }

    /**
     * The GLSL source as the bytes the driver will actually receive.
     *
     * <p>LWJGL 2 passes a CharSequence by narrowing every char to its low byte, so anything
     * outside ASCII arrives mangled — and the box-drawing character this shader rules its
     * sections off with, U+2500, narrows to 0x00. A NUL ends the source: the driver saw the
     * file stop at the first section header and reported "syntax error, unexpected $end at
     * token <EOF>". LWJGL 3 encodes properly, which is why the same file is fine on 1.21.x.
     *
     * <p>Every non-ASCII character in these files is decoration inside a comment, so they are
     * replaced with spaces rather than transliterated, and the buffer is built here so no
     * narrowing happens anywhere else.
     */
    private static ByteBuffer asciiSource(String src) {
        ByteBuffer buf = BufferUtils.createByteBuffer(src.length());
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            buf.put((byte) (c < 0x80 ? c : ' '));
        }
        buf.flip();
        return buf;
    }

    private static int compile(int type, String src, String label) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, asciiSource(src));
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            lastError = label + " compile failed: " + GL20.glGetShaderInfoLog(id, 8192);
            GL20.glDeleteShader(id);
            return 0;
        }
        return id;
    }

    private static void buildQuad() {
        float[] v = { -1, -1, 0, 0,   1, -1, 1, 0,   -1, 1, 0, 1,   1, 1, 1, 1 };
        FloatBuffer buf = BufferUtils.createFloatBuffer(v.length);
        buf.put(v).flip();
        vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);
        vbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 16, 0L);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 16, 8L);
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    /**
     * A 128x128 blue-noise-ish tile for the gather's per-pixel rotation. Generated rather than
     * shipped: the gather only needs the values to be well spread and repeatable, and a
     * void-and-cluster table would be another asset to keep in step with the other versions.
     */
    private static void buildNoise() {
        ByteBuffer b = BufferUtils.createByteBuffer(128 * 128);
        long s = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < 128 * 128; i++) {
            s ^= s << 13; s ^= s >>> 7; s ^= s << 17;
            b.put((byte) ((s >>> 33) & 0xFF));
        }
        b.flip();
        noiseTex = GL11.glGenTextures();
        int prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, noiseTex);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, 128, 128, 0,
                GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, b);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
    }

    private static String readResource(String path) {
        InputStream in = EvfBlurRenderer.class.getResourceAsStream(path);
        if (in == null) return null;
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, Charset.forName("UTF-8")));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
        } catch (Exception e) {
            return null;
        }
        return sb.toString();
    }
}
