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
    private static KeyMapping pinKey;
    private static KeyMapping freecamLockKey;
    private static KeyMapping pathMenuKey;

    public static float   aperture        = 5.6f;

    /**
     * Narrowest and widest f-number the barrel can physically reach, regardless of zoom.
     * The blades cannot open wider than the barrel, nor close past their own limit, so the
     * derived f-number below is clamped to this.
     */
    public static final float APERTURE_WIDEST    = 1.4f;
    public static final float APERTURE_NARROWEST = 32.0f;

    /**
     * Diameter of the entrance pupil in mm — the physical opening the blades form.
     *
     * <p>This, not the f-number, is what the aperture ring actually sets. An f-number is only
     * the ratio N = f / D, so it is not independent of focal length: hold the blades still and
     * zoom in, and N climbs on its own. That is why a kit zoom is "f/3.5-5.6" — same blades,
     * different focal length. Treating N as a free-standing knob (as this did) made the lens
     * behave like nothing that exists.
     *
     * <p>Set whenever the aperture is adjusted, consumed whenever the focal length changes.
     */
    public static float apertureDiameterMm = 50.0f / 5.6f;

    /** Records the blade opening implied by the current f-number and focal length. */
    public static void syncApertureDiameter() {
        if (focalLengthMm > 0 && aperture > 0f) apertureDiameterMm = focalLengthMm / aperture;
    }

    /**
     * Re-derives the f-number after a focal-length change, with the blades left where they are.
     * Clamped to what the barrel can do — at the wide end the blades would have to open past
     * the barrel, so N floors out and the diameter is re-synced to the truth.
     */
    public static void applyFocalLengthToAperture() {
        if (apertureDiameterMm <= 0f || focalLengthMm <= 0) return;
        float n = focalLengthMm / apertureDiameterMm;
        aperture = Math.max(APERTURE_WIDEST, Math.min(APERTURE_NARROWEST, n));
        if (aperture != n) syncApertureDiameter();   // hit a stop; blades really did move
        updateAutoValues();
    }

    public static int     shutterSpeedIdx = 10;
    public static int     iso             = 400;

    /** Where the image is ACTUALLY focused. Eased toward {@link #focusTarget}. */
    public static float   focusDistance   = 5.0f;

    /**
     * Where the focus ring is set — the destination, not the current state.
     *
     * <p>Manual focus used to write straight to {@link #focusDistance}, so every scroll click
     * was an instant jump. Small clicks hid it, but the last step to infinity multiplies the
     * distance tenfold in one go and the image snapped. Splitting ring position from lens
     * position lets the same rack that autofocus uses carry manual focus too.
     */
    public static float   focusTarget     = 5.0f;

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

    /**
     * Highlights high-contrast edges near the focus distance in the viewfinder, the same aid
     * a real mirrorless body draws for manual focus. Never baked into a photo or a recorded
     * frame — see {@code EvfBlurRenderer.applyBlur}'s {@code showPeaking} computation.
     */
    public static boolean focusPeaking = false;

    /**
     * Millimetres of subject distance per block — how big the world is to the lens. A
     * Minecraft block has no real-world size of its own, so this is a setting rather than a
     * constant; the screen shows it as centimetres per block, which is how a builder thinks
     * about it. Kept in millimetres because that is the unit the thin-lens formula wants.
     */
    public static float   dofScaleMm      = EvfBlurRenderer.DOF_SCALE_STILL;

    public static int     autoShutterIdx  = 10;
    public static float   autoAperture    = 5.6f;
    /**
     * The exact, unrounded shutter speed and aperture an auto axis is targeting — what the
     * exposure math should use, as opposed to {@link #autoShutterIdx}/{@link #autoAperture},
     * which are that same target rounded to the nearest marked stop for the readout. Reading
     * the rounded value back as the exposure itself reintroduced up to half a stop of
     * quantisation error, and because aperture moves continuously with zoom, an axis sitting
     * near a stop boundary could cross it on an imperceptible change and swing the photo a
     * full stop between frames.
     */
    public static double  autoShutterSecondsIdeal = 1.0 / 60.0;
    public static double  autoApertureIdeal       = 5.6;

    public static boolean viewfinderSneakEnabled = true;

    /**
     * Freecam's control feel: off is direct WASD flight; on trades that for inertia, an
     * altitude hold, and orbiting a dropped pin — suited to aerial footage rather than
     * precise still-photo positioning.
     */
    public static boolean droneMode = false;

    /**
     * When true, freecam does not force the player's body into view — useful for landscape
     * or wildlife footage the player's own frozen body has no business being in.
     */
    public static boolean freecamHidePlayer = false;

    /**
     * Whether the viewfinder should be showing right now — sneaking with the toggle on, OR
     * freecam, which is a photography tool in its own right. Sneak state itself means nothing
     * while freecam is active, since the player is frozen wherever it happened to be.
     */
    public static boolean viewfinderActive(Minecraft mc) {
        if (Freecam.isActive()) return true;
        return viewfinderSneakEnabled && mc.player != null && mc.player.isShiftKeyDown();
    }

    /**
     * Where the photograph is actually taken from — the render camera, not {@code mc.player}'s
     * eye. They coincide unless something is driving the camera independently of the player
     * (today, only {@link Freecam}), but reading it from here is what lets autofocus, depth of
     * field and the capture origin all follow it automatically.
     */
    public static net.minecraft.world.phys.Vec3 cameraPos(Minecraft mc) {
        return mc.gameRenderer.getMainCamera().position();
    }

    /** The render camera's look direction — see {@link #cameraPos}. */
    public static net.minecraft.world.phys.Vec3 cameraLook(Minecraft mc) {
        net.minecraft.client.Camera camera = mc.gameRenderer.getMainCamera();
        return net.minecraft.world.phys.Vec3.directionFromRotation(
                camera.xRot(), camera.yRot());
    }

    public static final double[] SHUTTER_SECONDS = {
            30.0, 15.0, 8.0, 4.0, 2.0, 1.0,
            0.5, 0.25, 0.125, 1.0/15, 1.0/30, 1.0/60,
            1.0/125, 1.0/250, 1.0/500, 1.0/1000, 1.0/2000, 1.0/4000
    };

    @Override
    public void onInitializeClient() {
        // Load persisted camera settings (exposure/focus mode, aperture, ISO, …).
        SnapmaticaConfig.load();

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

        pinKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.snapmatica.pin",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_X,
                category
        ));

        freecamLockKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.snapmatica.freecam_lock",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_C,
                category
        ));

        pathMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.snapmatica.path_menu",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_Q,
                category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            while (viewfinderSneakKey.consumeClick()) {
                viewfinderSneakEnabled = !viewfinderSneakEnabled;
                SnapmaticaConfig.save();
            }
            while (orientationKey.consumeClick()) {
                portraitOrientation = !portraitOrientation;
                SnapmaticaConfig.save();
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

            while (pinKey.consumeClick()) {
                Freecam.togglePin(client);
            }
            while (freecamLockKey.consumeClick()) {
                Freecam.toggleLock(client);
            }
            while (pathMenuKey.consumeClick()) {
                if (client.screen instanceof CameraPathScreen) {
                    client.setScreen(null);
                } else if (Freecam.isActive() && client.screen == null) {
                    client.setScreen(new CameraPathScreen());
                }
            }
            Freecam.tick(client);

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

        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("snapmatica", "freecam_hud"),
                FreecamHud::render
        );

        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("snapmatica", "camera_path"),
                CameraPathRenderer::render
        );

        // The depth copy happens BEFORE translucent terrain so glass cannot stamp its own
        // surface distance over the view through it — see PhotoCapture.onBeforeTranslucent().
        // The AF raycast stays at the end of the pass; it is a world query and does not care
        // where in the render it runs.
        LevelRenderEvents.BEFORE_TRANSLUCENT_TERRAIN.register(ctx -> PhotoCapture.onBeforeTranslucent());
        LevelRenderEvents.END_MAIN.register(ctx -> PhotoCapture.onWorldRenderEnd());

        System.out.println("[Snapmatica] Initialized.");
    }

    public static void updateAutoValues() {
        boolean ssAuto = (exposureMode == 1 || exposureMode == 3);
        boolean apAuto = (exposureMode == 2 || exposureMode == 3);

        if (ssAuto) {
            float ap = apAuto ? 5.6f : aperture;
            double targetSS = ap * ap * 400.0 / (60.0 * 31.36 * iso);
            autoShutterIdx = nearestShutterIdx(targetSS);
            autoShutterSecondsIdeal = targetSS;
        } else {
            autoShutterIdx = shutterSpeedIdx;
            autoShutterSecondsIdeal = SHUTTER_SECONDS[
                    Math.max(0, Math.min(SHUTTER_SECONDS.length - 1, shutterSpeedIdx))];
        }

        if (apAuto) {
            double ss = SHUTTER_SECONDS[Math.max(0, Math.min(SHUTTER_SECONDS.length - 1, shutterSpeedIdx))];
            double targetAp = 5.6 * Math.sqrt(ss * 60.0 * iso / 400.0);
            autoApertureIdeal = Math.max(1.4, Math.min(22.0, targetAp));
            autoAperture = nearestAperture((float) autoApertureIdeal);
        } else {
            autoAperture = aperture;
            autoApertureIdeal = aperture;
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
