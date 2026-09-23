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
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;

import java.nio.FloatBuffer;

/**
 * The aperture burst's sum, kept on the GPU.
 *
 * <p>Measured before this existed, 64 samples at 1080p: 763 ms of a 2675 ms burst went on the
 * sum alone -- each sub-frame read back as a NativeImage, then decoded to linear light and
 * added up pixel by pixel in Java, on the render thread, so the game stood still for it. The
 * GPU does the same arithmetic as a single full-screen draw with additive blending into a
 * 32-bit float target, and nothing comes back to the CPU until the burst is over.
 *
 * <p>Each sample still has to be matched in brightness to the first one (a shader pack's eye
 * adaptation re-meters per viewpoint -- see {@link ApertureIntegration}), and that needs the
 * frame's mean. Taking it with a synchronous read would stall the CPU on the GPU every
 * sample and give the saving straight back, so the sample is first decoded into a staging
 * texture, averaged by its own mipmap chain down to a single texel, and that one texel is
 * read ASYNCHRONOUSLY through a pixel buffer. The sample is added once its mean has arrived,
 * usually a frame or two later; three staging slots let new samples be decoded meanwhile.
 *
 * <p>The arithmetic is the CPU path's, constant for constant: the same sRGB decode, the same
 * Rec.709 luminance for the mean, the same gain and the same rejection band.
 */
@Environment(EnvType.CLIENT)
final class BurstAccumulator {
    private BurstAccumulator() {}

    private static final String VSH = """
            #version 150
            in vec2 Position;
            in vec2 UV0;
            out vec2 texCoord;
            void main() { gl_Position = vec4(Position, 0.0, 1.0); texCoord = UV0; }
            """;

    /** Mode 0 decodes the frame to linear light; mode 1 adds a staged sample, scaled. */
    private static final String FSH = """
            #version 150
            uniform sampler2D Src;
            uniform int   Mode;
            uniform float Gain;
            in vec2 texCoord;
            out vec4 fragColor;
            vec3 srgbToLinear(vec3 c) {
                return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
            }
            void main() {
                vec3 c = texture(Src, texCoord).rgb;
                if (Mode == 0) fragColor = vec4(srgbToLinear(c), 1.0);
                else           fragColor = vec4(c * Gain, 1.0);
            }
            """;

    private static int program = -1, locSrc, locMode, locGain;
    private static int vao = -1, vbo = -1;

    private static int w, h, levels;
    private static int accumTex = -1, accumFbo = -1;
    /**
     * Staging slots, as a ring: {@code head} is the oldest sample still waiting for its mean,
     * {@code count} how many are waiting. Three because the GPU runs one to two frames behind
     * the CPU: with two, the slot needed for the next sample was often still in flight, and
     * the CPU sat in glClientWaitSync for it -- measured at 4 ms a sample, a quarter of a
     * second over a 64-sample burst, spent doing nothing.
     */
    private static final int SLOTS = 3;
    private static final int[] stageTex = new int[SLOTS];
    private static final int[] stageFbo = new int[SLOTS];
    private static final int[] pbo = new int[SLOTS];
    private static final long[] fence = new long[SLOTS];
    private static int head = 0, count = 0;
    static {
        java.util.Arrays.fill(stageTex, -1);
        java.util.Arrays.fill(stageFbo, -1);
        java.util.Arrays.fill(pbo, -1);
    }

    /** Whether this GPU path is usable at all; false sends the burst back to the CPU sum. */
    static boolean ensureReady(int fbW, int fbH) {
        try {
            if (program == -1 && !initProgram()) return false;
            if (accumTex == -1 || w != fbW || h != fbH) allocate(fbW, fbH);
            return accumTex != -1;
        } catch (Throwable t) {
            System.err.println("[Snapmatica] GPU burst sum unavailable: " + t);
            return false;
        }
    }

    /** Clears the sum and forgets any staged samples, for a new burst. */
    static void begin(int fbW, int fbH) {
        ensureReady(fbW, fbH);
        for (int i = 0; i < SLOTS; i++) dropFence(i);
        head = 0; count = 0;
        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, accumFbo);
        GL11.glClearColor(0f, 0f, 0f, 0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
    }

    /**
     * Stages the frame now in the main framebuffer as one sample, and folds in whichever
     * earlier sample has its mean ready.
     *
     * @param sink told the outcome of every sample that gets folded in
     */
    static void submit(Minecraft mc, Sink sink) {
        int mainTex = mainColorTex(mc);
        if (mainTex <= 0) return;
        State st = State.save();
        try {
            // Fold in every earlier sample whose mean has ARRIVED -- asked, never waited for.
            while (count > 0 && signalled(head)) foldHead(sink);
            // Only when every slot is still in flight is there no choice but to wait, and then
            // only for the oldest.
            if (count == SLOTS) foldHead(sink);

            // Decode this frame into the free slot, average it down, and ask for the mean.
            int s = (head + count) % SLOTS;
            drawInto(stageFbo[s], mainTex, 0, 1f, false);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, stageTex[s]);
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[s]);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, levels - 1, GL11.GL_RGBA, GL11.GL_FLOAT, 0L);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            dropFence(s);
            fence[s] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            // Pushed to the driver now, so the fence can signal while the CPU gets on with the
            // next frame instead of only when someone finally asks.
            GL11.glFlush();
            count++;
        } finally {
            st.restore();
        }
    }

    /** Folds in whatever is still staged. Called once, when the last sample has been taken. */
    static void flush(Sink sink) {
        State st = State.save();
        try {
            // Oldest first, so the samples go in the order they were taken.
            while (count > 0) foldHead(sink);
        } finally {
            st.restore();
        }
    }

    /** True while a sample is staged and not yet folded in. */
    static boolean hasPending() { return count > 0; }

    /**
     * The finished sum as three planes in NativeImage row order (top row first), which is
     * what {@code ApertureIntegration.finish} reads. The GPU's rows run bottom-up.
     */
    static float[][] readSum() {
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        FloatBuffer buf = BufferUtils.createFloatBuffer(w * h * 4);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, accumTex);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_FLOAT, buf);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        float[] r = new float[w * h], g = new float[w * h], b = new float[w * h];
        for (int y = 0; y < h; y++) {
            int src = (h - 1 - y) * w * 4, dst = y * w;
            for (int x = 0; x < w; x++, src += 4) {
                r[dst + x] = buf.get(src);
                g[dst + x] = buf.get(src + 1);
                b[dst + x] = buf.get(src + 2);
            }
        }
        return new float[][]{r, g, b};
    }

    static int width()  { return w; }
    static int height() { return h; }

    /** What happened to one sample: its metered mean, and the gain it went in at. */
    interface Sink { boolean accept(double mean); double gain(); }

    // ── internals ────────────────────────────────────────────────────────────────

    /** Whether a slot's mean has come back, without waiting for it. */
    private static boolean signalled(int s) {
        return fence[s] == 0L
                || GL32.glGetSynci(fence[s], GL32.GL_SYNC_STATUS, null) == GL32.GL_SIGNALED;
    }

    private static void foldHead(Sink sink) {
        fold(head, sink);
        head = (head + 1) % SLOTS;
        count--;
    }

    private static void fold(int s, Sink sink) {
        if (fence[s] != 0L) {
            // Returns at once for a signalled fence; only the forced case above ever waits.
            GL32.glClientWaitSync(fence[s], GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
            dropFence(s);
        }
        FloatBuffer px = BufferUtils.createFloatBuffer(4);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[s]);
        GL15.glGetBufferSubData(GL21.GL_PIXEL_PACK_BUFFER, 0L, px);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        double mean = 0.2126 * px.get(0) + 0.7152 * px.get(1) + 0.0722 * px.get(2);
        if (!sink.accept(mean)) return;             // rejected: never reaches the sum
        GL11.glEnable(GL11.GL_BLEND);
        GL14Blend.additive();
        drawInto(accumFbo, stageTex[s], 1, (float) sink.gain(), true);
        GL11.glDisable(GL11.GL_BLEND);
    }

    private static void drawInto(int fbo, int srcTex, int mode, float gain, boolean blend) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL11.glViewport(0, 0, w, h);
        GL20.glUseProgram(program);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, srcTex);
        GL20.glUniform1i(locSrc, 0);
        GL20.glUniform1i(locMode, mode);
        GL20.glUniform1f(locGain, gain);
        GL30.glBindVertexArray(vao);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
    }

    private static void dropFence(int s) {
        if (fence[s] != 0L) { GL32.glDeleteSync(fence[s]); fence[s] = 0L; }
    }

    private static int mainColorTex(Minecraft mc) {
        RenderTarget fb = mc.gameRenderer.mainRenderTarget();
        com.mojang.renderpearl.api.textures.GpuTexture gpuTex = fb.getColorTexture();
        return (gpuTex instanceof com.mojang.renderpearl.backend.opengl.GlTexture glTex) ? glTex.glId() : 0;
    }

    private static boolean initProgram() {
        int vs = compile(GL20.GL_VERTEX_SHADER, VSH);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, FSH);
        if (vs == -1 || fs == -1) return false;
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        GL20.glBindAttribLocation(p, 0, "Position");
        GL20.glBindAttribLocation(p, 1, "UV0");
        GL20.glLinkProgram(p);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            System.err.println("[Snapmatica] burst sum link error: " + GL20.glGetProgramInfoLog(p));
            GL20.glDeleteProgram(p);
            return false;
        }
        program = p;
        locSrc  = GL20.glGetUniformLocation(p, "Src");
        locMode = GL20.glGetUniformLocation(p, "Mode");
        locGain = GL20.glGetUniformLocation(p, "Gain");

        float[] verts = { -1f, -1f, 0f, 0f,   1f, -1f, 1f, 0f,   -1f, 1f, 0f, 1f,   1f, 1f, 1f, 1f };
        FloatBuffer vb = BufferUtils.createFloatBuffer(verts.length);
        vb.put(verts).flip();
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);
        vbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vb, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 16, 0L);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 16, 8L);
        GL20.glEnableVertexAttribArray(1);
        GL30.glBindVertexArray(prevVao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevBuf);
        return true;
    }

    private static int compile(int type, String src) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, src);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            System.err.println("[Snapmatica] burst sum shader error: " + GL20.glGetShaderInfoLog(id));
            GL20.glDeleteShader(id);
            return -1;
        }
        return id;
    }

    private static void allocate(int fbW, int fbH) {
        release();
        w = fbW; h = fbH;
        levels = 1 + (int) Math.floor(Math.log(Math.max(w, h)) / Math.log(2));
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);

        accumTex = texture(GL30.GL_RGBA32F, 1);
        accumFbo = fboFor(accumTex);
        for (int i = 0; i < SLOTS; i++) {
            // Half float is ample for one decoded 8-bit frame; the sum itself is 32-bit.
            stageTex[i] = texture(GL30.GL_RGBA16F, levels);
            stageFbo[i] = fboFor(stageTex[i]);
            pbo[i] = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[i]);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, 16L, GL15.GL_STREAM_READ);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            System.err.println("[Snapmatica] burst sum framebuffer incomplete: 0x"
                    + Integer.toHexString(status));
            release();
        }
    }

    private static int texture(int internal, int lv) {
        int t = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, t);
        // Level by level rather than glTexStorage2D: that is GL 4.2, and macOS stops at 4.1,
        // where calling it would not fail politely.
        int tw = w, th = h;
        for (int i = 0; i < lv; i++) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, i, internal, tw, th, 0,
                    GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
            tw = Math.max(1, tw / 2);
            th = Math.max(1, th / 2);
        }
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, lv - 1);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        return t;
    }

    private static int fboFor(int tex) {
        int f = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, f);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, tex, 0);
        return f;
    }

    private static void release() {
        if (accumTex != -1) { GL11.glDeleteTextures(accumTex); accumTex = -1; }
        if (accumFbo != -1) { GL30.glDeleteFramebuffers(accumFbo); accumFbo = -1; }
        for (int i = 0; i < SLOTS; i++) {
            if (stageTex[i] != -1) { GL11.glDeleteTextures(stageTex[i]); stageTex[i] = -1; }
            if (stageFbo[i] != -1) { GL30.glDeleteFramebuffers(stageFbo[i]); stageFbo[i] = -1; }
            if (pbo[i] != -1) { GL15.glDeleteBuffers(pbo[i]); pbo[i] = -1; }
            dropFence(i);
        }
        head = 0; count = 0;
    }

    /** Additive blending, kept apart so the state it touches is named in one place. */
    private static final class GL14Blend {
        static void additive() {
            org.lwjgl.opengl.GL14.glBlendEquation(org.lwjgl.opengl.GL14.GL_FUNC_ADD);
            GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
        }
    }

    /**
     * Everything this touches, put back afterwards. Minecraft caches GL state on its side, so
     * leaving any of it changed would surface as someone else's rendering bug.
     */
    private static final class State {
        int program, fbo, readFbo, drawFbo, vao, arrayBuf, packBuf, activeTex, tex0, sampler0;
        int blendSrcRgb, blendDstRgb, blendSrcA, blendDstA, blendEq;
        boolean blend, depth, scissor, cull;
        final int[] viewport = new int[4];

        static State save() {
            State s = new State();
            s.program  = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            s.fbo      = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            s.readFbo  = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            s.drawFbo  = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            s.vao      = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            s.arrayBuf = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            s.packBuf  = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            s.activeTex = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            s.tex0     = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            s.sampler0 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
            // A sampler object bound by Minecraft would override our NEAREST sampling.
            GL33.glBindSampler(0, 0);
            s.blend   = GL11.glIsEnabled(GL11.GL_BLEND);
            s.depth   = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            s.scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            s.cull    = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            s.blendSrcRgb = GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_SRC_RGB);
            s.blendDstRgb = GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_DST_RGB);
            s.blendSrcA   = GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_SRC_ALPHA);
            s.blendDstA   = GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_DST_ALPHA);
            s.blendEq     = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, s.viewport);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_BLEND);
            return s;
        }

        void restore() {
            if (blend) GL11.glEnable(GL11.GL_BLEND); else GL11.glDisable(GL11.GL_BLEND);
            if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST); else GL11.glDisable(GL11.GL_DEPTH_TEST);
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST); else GL11.glDisable(GL11.GL_SCISSOR_TEST);
            if (cull) GL11.glEnable(GL11.GL_CULL_FACE); else GL11.glDisable(GL11.GL_CULL_FACE);
            org.lwjgl.opengl.GL14.glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcA, blendDstA);
            org.lwjgl.opengl.GL14.glBlendEquation(blendEq);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex0);
            GL33.glBindSampler(0, sampler0);
            GL13.glActiveTexture(activeTex);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuf);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuf);
            GL30.glBindVertexArray(vao);
            GL20.glUseProgram(program);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
        }
    }
}
