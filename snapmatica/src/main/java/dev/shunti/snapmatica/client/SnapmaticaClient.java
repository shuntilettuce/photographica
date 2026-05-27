package dev.shunti.snapmatica.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

@Environment(EnvType.CLIENT)
public class SnapmaticaClient implements ClientModInitializer {

    // ── Key Bindings ─────────────────────────────────────────────────────────────
    private static KeyBinding shootKey;
    private static KeyBinding settingsKey;
    private static KeyBinding viewfinderSneakKey;  // toggle sneak-to-viewfinder mode
    // ── Camera state (client-side only, no server sync needed) ───────────────────
    public static float aperture = 5.6f;
    public static int shutterSpeedIdx = 10;      // index into SHUTTER_SECONDS[] (1/60)
    public static int iso = 400;
    public static float focusDistance = 5.0f;
    public static int focalLengthMm = 50;
    public static int lensType = 1;               // LensKind.PRIME_50MM
    public static int exposureMode = 0;           // M (manual)
    public static int focusMode = 0;              // MF (manual focus)
    public static boolean motionBlur = false;
    public static int timerSeconds = 0;

    /** When true, sneaking shows the viewfinder overlay (default: enabled). */
    public static boolean viewfinderSneakEnabled = true;

    // Shutter speed table (same as Photographica's CameraSettings)
    public static final double[] SHUTTER_SECONDS = {
            30.0, 15.0, 8.0, 4.0, 2.0, 1.0,
            0.5, 0.25, 0.125, 1.0 / 15, 1.0 / 30, 1.0 / 60,
            1.0 / 125, 1.0 / 250, 1.0 / 500, 1.0 / 1000, 1.0 / 2000, 1.0 / 4000
    };

    @Override
    public void onInitializeClient() {
        // ── Register key bindings ───────────────────────────────────────────────
        shootKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.shoot",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_P,             // default: P
                "category.snapmatica"
        ));

        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.settings",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,       // unbound by default
                "category.snapmatica"
        ));

        viewfinderSneakKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.snapmatica.viewfinder_sneak",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,       // unbound by default — assign your own key
                "category.snapmatica"
        ));

        // ── Tick handler ─────────────────────────────────────────────────────────
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            // Toggle the sneak-to-viewfinder mode
            while (viewfinderSneakKey.wasPressed()) {
                viewfinderSneakEnabled = !viewfinderSneakEnabled;
            }

            // Shoot key pressed
            if (shootKey.wasPressed()) {
                PhotoCapture.take();
            }

            // Settings key pressed
            if (settingsKey.wasPressed()) {
                client.setScreen(new CameraScreen());
            }
        });

        // ── HUD overlay (viewfinder, blackout, flash) ───────────────────────────
        HudRenderCallback.EVENT.register(ViewfinderOverlay::render);

        // ── World render end (depth capture, etc.) ──────────────────────────────
        WorldRenderEvents.LAST.register(ctx -> {
            PhotoCapture.onWorldRenderEnd();
        });

        System.out.println("[Snapmatica] Initialized.");
    }
}

