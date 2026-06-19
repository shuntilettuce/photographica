package dev.shunti.snapmatica.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

@Environment(EnvType.CLIENT)
public class SnapmaticaClient implements ClientModInitializer {

    private static KeyMapping shootKey;
    private static KeyMapping settingsKey;
    private static KeyMapping viewfinderSneakKey;
    private static KeyMapping orientationKey;
    private static KeyMapping recordKey;

    public static float   aperture        = 5.6f;
    public static int     shutterSpeedIdx = 10;
    public static int     iso             = 400;
    public static float   focusDistance   = 5.0f;

    /**
     * Focus-distance sentinel meaning "optical infinity". 100 km — far above any
     * Minecraft raycast (<=1000 m) or Distant Horizons distance so real subjects
     * always focus finitely, never collapsing to infinity prematurely.
     */
    public static final float FOCUS_INFINITY = 100000.0f;

    /** When true, the viewfinder and saved photo use a 2:3 portrait frame. */
    public static boolean portraitOrientation = false;

    public static int     focalLengthMm   = 50;
    public static int     lensType        = 1;
    public static int     exposureMode    = 0;
    public static int     focusMode       = 0;
    public static boolean motionBlur      = false;

    public static int     autoShutterIdx  = 10;
    public static float   autoAperture    = 5.6f;

    public static boolean viewfinderSneakEnabled = true;

    public static final double[] SHUTTER_SECONDS = {
            30.0, 15.0, 8.0, 4.0, 2.0, 1.0,
            0.5, 0.25, 0.125, 1.0/15, 1.0/30, 1.0/60,
            1.0/125, 1.0/250, 1.0/500, 1.0/1000, 1.0/2000, 1.0/4000
    };

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("snapmatica", "category"));

        shootKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.snapmatica.shoot",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_ENTER,
                category
        ));

        settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.snapmatica.settings",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                category
        ));

        viewfinderSneakKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.snapmatica.viewfinder_sneak",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_COMMA,
                category
        ));

        orientationKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.snapmatica.orientation",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                category
        ));

        recordKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.snapmatica.record",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            while (viewfinderSneakKey.consumeClick()) {
                viewfinderSneakEnabled = !viewfinderSneakEnabled;
            }
            while (orientationKey.consumeClick()) {
                portraitOrientation = !portraitOrientation;
            }
            while (recordKey.consumeClick()) {
                if (VideoRecorder.isRecording()) VideoRecorder.stopRecording();
                else if (!VideoRecorder.isPostProcessing()) client.setScreen(new VideoRecorderScreen());
            }
            if (shootKey.consumeClick()) {
                PhotoCapture.take();
            }
            if (settingsKey.consumeClick()) {
                client.setScreen(new CameraScreen());
            }

            AutoFocus.tick(client);
            updateAutoValues();
        });

        HudElementRegistry.addFirst(
                Identifier.fromNamespaceAndPath("snapmatica", "viewfinder"),
                ViewfinderOverlay::extractRenderState
        );

        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("snapmatica", "video_recorder"),
                VideoRecorderHud::render
        );

        LevelRenderEvents.END_MAIN.register(ctx -> {
            PhotoCapture.onWorldRenderEnd();
            VideoRecorder.onWorldRenderEnd();
        });

        System.out.println("[Snapmatica] Initialized.");
    }

    public static void updateAutoValues() {
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
