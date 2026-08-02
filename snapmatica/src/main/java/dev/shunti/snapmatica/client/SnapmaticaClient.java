package dev.shunti.snapmatica.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
//? if >=1.21.11 {
/*import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.util.Identifier;
*///?} else {
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
//?}
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

@Environment(EnvType.CLIENT)
public class SnapmaticaClient implements ClientModInitializer {


    //? if >=1.21.11 {
    /*private static final KeyBinding.Category SNAPMATICA_CATEGORY =
            new KeyBinding.Category(Identifier.of("snapmatica", "snapmatica"));
    *///?}

    // ── Key Bindings ─────────────────────────────────────────────────────────────
    private static KeyBinding shootKey;
    private static KeyBinding settingsKey;
    private static KeyBinding viewfinderSneakKey;  // toggle sneak-to-viewfinder mode
    private static KeyBinding orientationKey;       // toggle portrait/landscape framing
    private static KeyBinding recordKey;            // start/stop video recording
    // ── Camera state (client-side only, no server sync needed) ───────────────────
    public static float aperture = 5.6f;
    public static int shutterSpeedIdx = 10;      // index into SHUTTER_SECONDS[] (1/30)
    public static int iso = 400;
    public static float focusDistance = 5.0f;
    /**
     * Focus-distance sentinel meaning "optical infinity" (no finite subject). Set far
     * above any real Minecraft raycast (≤1000) or Distant Horizons (km-scale) distance so
     * it never collides with a measurement — a genuine 2000 m subject focuses finitely at
     * 2000 m, and only sky / no-hit collapses to infinity.
     */
    public static final float FOCUS_INFINITY = 100000.0f;
    public static int focalLengthMm = 50;
    public static int lensType = 1;               // LensKind.PRIME_50MM
    public static int exposureMode = 0;           // M (manual)
    public static int focusMode = 0;              // MF (manual focus)
    public static boolean motionBlur = false;

    /** Auto-computed shutter index (used when SS is in AUTO mode). */
    public static int autoShutterIdx = 10;
    /** Auto-computed aperture (used when aperture is in AUTO mode). */
    public static float autoAperture = 5.6f;

    /** When true, sneaking shows the viewfinder overlay (default: enabled). */
    public static boolean viewfinderSneakEnabled = true;

    /** When true, the viewfinder and saved photo use a 2:3 portrait frame instead of 3:2. */
    public static boolean portraitOrientation = false;

    // Shutter speed table (same as Photographica's CameraSettings)
    public static final double[] SHUTTER_SECONDS = {
            30.0, 15.0, 8.0, 4.0, 2.0, 1.0,
            0.5, 0.25, 0.125, 1.0 / 15, 1.0 / 30, 1.0 / 60,
            1.0 / 125, 1.0 / 250, 1.0 / 500, 1.0 / 1000, 1.0 / 2000, 1.0 / 4000
    };

    @Override
    public void onInitializeClient() {
        // ── Load persisted settings (sneak-viewfinder toggle, etc.) ─────────────
        SnapmaticaConfig.load();

        // ── Register key bindings ───────────────────────────────────────────────
        //? if >=1.21.11 {
        /*shootKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.shoot", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_ENTER, SNAPMATICA_CATEGORY));
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, SNAPMATICA_CATEGORY));
        viewfinderSneakKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.viewfinder_sneak", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_COMMA, SNAPMATICA_CATEGORY));
        orientationKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.orientation", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, SNAPMATICA_CATEGORY));
        recordKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.record", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, SNAPMATICA_CATEGORY));
        *///?} else {
        shootKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.shoot", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_ENTER, "category.snapmatica"));
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "category.snapmatica"));
        viewfinderSneakKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.viewfinder_sneak", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_COMMA, "category.snapmatica"));
        orientationKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.orientation", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, "category.snapmatica"));
        recordKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.record", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, "category.snapmatica"));
        //?}

        // ── Tick handler ─────────────────────────────────────────────────────────
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            // Toggle the sneak-to-viewfinder mode (persisted across sessions)
            while (viewfinderSneakKey.wasPressed()) {
                viewfinderSneakEnabled = !viewfinderSneakEnabled;
                SnapmaticaConfig.save();
            }

            // Toggle portrait / landscape framing
            while (orientationKey.wasPressed()) {
                portraitOrientation = !portraitOrientation;
                SnapmaticaConfig.save();
            }

            // Recording key: open settings screen when idle, stop directly when recording
            while (recordKey.wasPressed()) {
                if (VideoRecorder.isRecording()) VideoRecorder.stopRecording();
                else if (!VideoRecorder.isPostProcessing()) client.setScreen(new VideoRecorderScreen());
            }

            // Shoot key
            if (shootKey.wasPressed()) {
                PhotoCapture.take();
            }

            // Settings key
            if (settingsKey.wasPressed()) {
                client.setScreen(new CameraScreen());
            }

            // Auto-focus (AF / MOB) drives focusDistance while the viewfinder is active
            AutoFocus.tick(client);
            // Keep auto exposure values current every tick
            updateAutoValues();
        });

        // ── HUD overlay (viewfinder, blackout, flash, video REC) ────────────────
        HudRenderCallback.EVENT.register(ViewfinderOverlay::render);
        HudRenderCallback.EVENT.register(VideoRecorderHud::render);

        // ── World render end (depth capture, etc.) ──────────────────────────────
        //? if >=1.21.11 {
        /*WorldRenderEvents.END_MAIN.register(ctx -> {
            PhotoCapture.onWorldRenderEnd();
            VideoRecorder.onWorldRenderEnd();
        });
        *///?} else {
        WorldRenderEvents.LAST.register(ctx -> {
            PhotoCapture.onWorldRenderEnd();
            VideoRecorder.onWorldRenderEnd();
        });
        //?}

        System.out.println("[Snapmatica] Initialized.");
    }

    /**
     * Recomputes autoShutterIdx / autoAperture so that the exposure meter stays
     * centred (EV deviation = 0) regardless of ISO or the manually-set value.
     *
     * Reference point: f/5.6, 1/60 s, ISO 400 → EV deviation = 0.
     *   Center condition: ss * 60.0 * (5.6/ap)² * (iso/400) = 1
     *
     * Call this synchronously whenever aperture, ISO, or exposureMode changes.
     */
    public static void updateAutoValues() {
        // EXP_AV=1 (aperture priority) → SS is auto
        // EXP_TV=2 (shutter priority)  → aperture is auto
        // EXP_P=3  (program)           → both auto (fix ap=5.6)
        boolean ssAuto = (exposureMode == 1 || exposureMode == 3);
        boolean apAuto = (exposureMode == 2 || exposureMode == 3);

        if (ssAuto) {
            float ap = apAuto ? 5.6f : aperture;
            double targetSS = ap * ap * 400.0 / (60.0 * 31.36 * iso);
            autoShutterIdx = nearestShutterIdx(targetSS);
        } else {
            autoShutterIdx = shutterSpeedIdx;
        }

        if (apAuto) {
            double ss = SHUTTER_SECONDS[Math.max(0, Math.min(SHUTTER_SECONDS.length - 1, shutterSpeedIdx))];
            double targetAp = 5.6 * Math.sqrt(ss * 60.0 * iso / 400.0);
            autoAperture = nearestAperture((float) Math.max(1.4, Math.min(22.0, targetAp)));
        } else {
            autoAperture = aperture;
        }
    }

    private static int nearestShutterIdx(double ss) {
        ss = Math.max(1e-6, ss);
        int best = 0; double bestDiff = Double.MAX_VALUE;
        for (int i = 0; i < SHUTTER_SECONDS.length; i++) {
            double d = Math.abs(Math.log(SHUTTER_SECONDS[i]) - Math.log(ss));
            if (d < bestDiff) { bestDiff = d; best = i; }
        }
        return best;
    }

    private static final float[] APERTURE_STOPS = {1.4f, 2.0f, 2.8f, 4.0f, 5.6f, 8.0f, 11.0f, 16.0f, 22.0f};

    private static float nearestAperture(float ap) {
        float best = APERTURE_STOPS[0]; float bestDiff = Float.MAX_VALUE;
        for (float a : APERTURE_STOPS) {
            float d = Math.abs(a - ap);
            if (d < bestDiff) { bestDiff = d; best = a; }
        }
        return best;
    }
}

