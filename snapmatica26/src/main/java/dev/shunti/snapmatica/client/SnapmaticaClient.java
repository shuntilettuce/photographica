package dev.shunti.snapmatica.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

@Environment(EnvType.CLIENT)
public class SnapmaticaClient implements ClientModInitializer {

    private static KeyMapping shootKey;
    private static KeyMapping settingsKey;
    private static KeyMapping viewfinderSneakKey;

    public static float   aperture        = 5.6f;
    public static int     shutterSpeedIdx = 10;
    public static int     iso             = 400;
    public static float   focusDistance   = 5.0f;
    public static int     focalLengthMm   = 50;
    public static int     lensType        = 1;
    public static int     exposureMode    = 0;
    public static int     focusMode       = 0;
    public static boolean motionBlur      = false;

    public static boolean viewfinderSneakEnabled = true;

    public static final double[] SHUTTER_SECONDS = {
            30.0, 15.0, 8.0, 4.0, 2.0, 1.0,
            0.5, 0.25, 0.125, 1.0/15, 1.0/30, 1.0/60,
            1.0/125, 1.0/250, 1.0/500, 1.0/1000, 1.0/2000, 1.0/4000
    };

    @Override
    public void onInitializeClient() {
        shootKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.snapmatica.shoot",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                "category.snapmatica"
        ));

        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.snapmatica.settings",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                "category.snapmatica"
        ));

        viewfinderSneakKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.snapmatica.viewfinder_sneak",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                "category.snapmatica"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            while (viewfinderSneakKey.consumeClick()) {
                viewfinderSneakEnabled = !viewfinderSneakEnabled;
            }
            if (shootKey.consumeClick()) {
                PhotoCapture.take();
            }
            if (settingsKey.consumeClick()) {
                client.setScreen(new CameraScreen());
            }

            AutoFocus.tick(client);
        });

        HudElementRegistry.addFirst(
                ResourceLocation.fromNamespaceAndPath("snapmatica", "viewfinder"),
                ViewfinderOverlay::render
        );

        LevelRenderEvents.END_MAIN.register(ctx -> PhotoCapture.onWorldRenderEnd());

        System.out.println("[Snapmatica] Initialized.");
    }
}
