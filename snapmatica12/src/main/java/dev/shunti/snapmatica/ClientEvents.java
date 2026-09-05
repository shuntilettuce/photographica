package dev.shunti.snapmatica;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Keyboard;

/**
 * Where the camera hooks into the frame, and how it is driven.
 *
 * <p>On Fabric this needed mixins into GameRenderer and WorldRenderer. Forge publishes both
 * points as events, so there are none: {@link RenderWorldLastEvent} fires with the world's
 * framebuffer and projection still bound, which is exactly where the depth has to be taken,
 * and the overlay events bracket the HUD.
 *
 * <p>The controls are the 1.21.x ones, unchanged — sneak to raise the viewfinder, wheel to
 * zoom, Ctrl for the aperture, Ctrl+Alt for focus, Enter to shoot. Anyone who has used the
 * modern version should not have to learn this one.
 */
public final class ClientEvents {

    private static final KeyBinding VIEWFINDER_SNEAK = new KeyBinding(
            "key.snapmatica.viewfinder_sneak", Keyboard.KEY_COMMA, "key.categories.snapmatica");
    private static final KeyBinding ORIENTATION = new KeyBinding(
            "key.snapmatica.orientation", Keyboard.KEY_V, "key.categories.snapmatica");
    private static final KeyBinding SHOOT = new KeyBinding(
            "key.snapmatica.shoot", Keyboard.KEY_RETURN, "key.categories.snapmatica");
    /** Dumps the next few seconds of per-frame render state to the log. */
    private static final KeyBinding TRACE = new KeyBinding(
            "key.snapmatica.trace", Keyboard.KEY_P, "key.categories.snapmatica");

    private static boolean warnedShaders = false;
    private static boolean reportedOnce  = false;

    public static void register() {
        ClientRegistry.registerKeyBinding(VIEWFINDER_SNEAK);
        ClientRegistry.registerKeyBinding(ORIENTATION);
        ClientRegistry.registerKeyBinding(SHOOT);
        ClientRegistry.registerKeyBinding(TRACE);
    }

    /**
     * The viewfinder is up while sneaking, the way it is on 1.21.x. Sneaking is what a person
     * does to steady a shot, and it keeps the camera out of the way the rest of the time
     * without spending a toggle on it.
     */
    public static boolean viewfinderActive() {
        Minecraft mc = Minecraft.getMinecraft();
        return CameraState.viewfinderSneakEnabled
            && mc.player != null && mc.player.isSneaking()
            && mc.currentScreen == null;
    }

    /** Focus distance in blocks: the ring's value, or whatever the reticle is on in AF. */
    public static float focusBlocks() {
        if (!CameraState.autoFocus()) return CameraState.focusDist();
        // Display only: the shader does the real autofocus from the depth under the reticle,
        // which is why AfMode exists. This is a separate ray purely so the readout has a
        // number, and it runs to 256 blocks rather than to the player's reach -- objectMouseOver
        // stops at about four and a half, so it reported infinity the moment the reticle left
        // the nearest block, and the readout flickered between arm's length and the horizon.
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null) return CameraState.FOCUS_INFINITY;
        Vec3d eye = mc.player.getPositionEyes(1.0f);
        Vec3d end = eye.add(mc.player.getLook(1.0f).scale(256.0));
        RayTraceResult r = mc.world.rayTraceBlocks(eye, end, false, true, false);
        if (r != null && r.typeOfHit != RayTraceResult.Type.MISS && r.hitVec != null) {
            double d = eye.distanceTo(r.hitVec);
            if (d > 0.05) return (float) d;
        }
        return CameraState.FOCUS_INFINITY;
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (VIEWFINDER_SNEAK.isPressed()) {
            CameraState.viewfinderSneakEnabled = !CameraState.viewfinderSneakEnabled;
            chat("Snapmatica: viewfinder " + (CameraState.viewfinderSneakEnabled
                    ? "on (sneak to raise it)" : "off"));
        }
        if (ORIENTATION.isPressed()) {
            CameraState.portrait = !CameraState.portrait;
            if (mc.ingameGUI != null) {
                mc.ingameGUI.setOverlayMessage(
                        new TextComponentString(CameraState.portrait ? "2:3 V" : "3:2 H"), false);
            }
        }
        if (SHOOT.isPressed() && viewfinderActive()) PhotoCapture.request();
        // Drained, then acted on once. isPressed() consumes one queued press per call, so a
        // held or repeated key toggled an even number of times and appeared to do nothing.
        boolean tracePressed = false;
        while (TRACE.isPressed()) tracePressed = true;
        if (tracePressed) {
            EvfBlurRenderer.toggleTrace();
            chat("Snapmatica: trace " + (EvfBlurRenderer.trace ? "ON" : "off"));
        }
    }

    /**
     * The focal length is the field of view. They are the same statement about the lens, and a
     * zoom ring that changed the depth of field without changing what fits in the frame would
     * be describing a lens that does not exist.
     *
     * <p>The 24 mm frame height is the anchor the whole optics model uses — the same 12 mm
     * half-height the shader projects with — so the angle here and the circle of confusion
     * there are derived from one number and cannot drift apart.
     */
    @SubscribeEvent
    public void onFov(EntityViewRenderEvent.FOVModifier event) {
        if (!viewfinderActive()) return;
        double halfHeightMm = 12.0;
        event.setFOV((float) Math.toDegrees(
                2.0 * Math.atan(halfHeightMm / Math.max(CameraState.focalLenMm(), 1))));
    }

    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        if (event.getDwheel() == 0) return;
        if (CameraScrollHandler.onScroll(event.getDwheel())) event.setCanceled(true);
    }

    /**
     * The held item is not part of the photograph. It is drawn into the same framebuffer as the
     * world, centimetres from the lens, so the defocus treats it as the nearest possible
     * foreground and smears it over a third of the frame.
     */
    @SubscribeEvent
    public void onRenderHand(RenderHandEvent event) {
        if (viewfinderActive() && !shadersActive()) event.setCanceled(true);
    }

    /** The world's framebuffer and projection are both current here, and the HUD has not yet
     *  drawn over the depth. */
    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!viewfinderActive() || shadersActive()) return;
        EvfBlurRenderer.captureDepth();
    }

    /**
     * Refuses to run under an OptiFine shaderpack.
     *
     * <p>The defocus reads the frame out of whatever framebuffer is bound and writes the result
     * back into it. OptiFine's shader pipeline does not assemble the frame there, and our
     * output lands somewhere that is never cleared, returning as the next frame's input — the
     * in-focus band, whose value is a straight copy of the source, then paints over itself
     * indefinitely. It appears the instant that pipeline is switched on, internal programs
     * included, and never on the vanilla path.
     *
     * <p>Deliberately not chased further. The pipeline is undocumented and every pack arranges
     * its passes differently, so the mechanism cannot be established by measurement — and
     * guessing at it is how a fix turns into a pile of workarounds that hide a problem instead
     * of removing it. A shaderpack also brings its own tone mapping, bloom and often its own
     * depth of field, so stacking a second lens model on top was never the right answer.
     */
    @SubscribeEvent
    public void onOverlayPre(RenderGameOverlayEvent.Pre event) {
        if (!viewfinderActive()) return;
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
        if (fastRenderActive()) {
            if (!warnedFastRender) {
                warnedFastRender = true;
                chat("Snapmatica: OptiFine's Fast Render is on, which replaces the render path "
                   + "the camera reads from. Turn it off in Video Settings > Performance.");
            }
            return;
        }
        warnedFastRender = false;
        if (shadersActive()) {
            if (!warnedShaders) {
                warnedShaders = true;
                chat("Snapmatica: an OptiFine shaderpack is active, so the camera is off. "
                   + "Set Shaders to (none) to use it.");
            }
            return;
        }
        warnedShaders = false;
        EvfBlurRenderer.renderBlur(focusBlocks(), CameraState.aperture(),
                                   CameraState.focalLenMm(), CameraState.DOF_SCALE,
                                   CameraState.autoFocus());
        // The photograph is read here, with the defocus applied and no interface over it yet.
        if (PhotoCapture.isPending()) {
            PhotoCapture.captureNow(EvfBlurRenderer.lastWidth, EvfBlurRenderer.lastHeight);
        }
        if (!reportedOnce) {
            reportedOnce = true;
            if (EvfBlurRenderer.lastError != null) chat("Snapmatica: " + EvfBlurRenderer.lastError);
        }
    }

    /** The viewfinder goes over the HUD: it is what you are looking through. */
    @SubscribeEvent
    public void onOverlayPost(RenderGameOverlayEvent.Post event) {
        if (!viewfinderActive() || shadersActive()) return;
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
        ViewfinderOverlay.render(focusBlocks(), CameraState.aperture(),
                                 CameraState.focalLenMm(), CameraState.autoFocus());
    }

    // ── OptiFine, asked for reflectively so it never becomes a compile dependency ────────

    private static Boolean shaderCheckFailed = null;
    private static java.lang.reflect.Method isShaders = null;
    private static java.lang.reflect.Method isFastRender = null;
    private static boolean fastRenderLookupFailed = false;
    private static boolean warnedFastRender = false;

    /**
     * Whether OptiFine's Fast Render is on.
     *
     * <p>It replaces enough of the render path that the frame cannot be read back and written
     * to the way this mod does, and the camera simply stops working — silently, which is the
     * worst way for it to fail. Detected so it can say so instead.
     */
    public static boolean fastRenderActive() {
        if (fastRenderLookupFailed) return false;
        try {
            if (isFastRender == null) {
                Class<?> c;
                try {
                    c = Class.forName("net.optifine.Config");
                } catch (ClassNotFoundException e) {
                    c = Class.forName("Config");
                }
                isFastRender = c.getMethod("isFastRender");
            }
            Object r = isFastRender.invoke(null);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            fastRenderLookupFailed = true;
            return false;
        }
    }

    public static boolean shadersActive() {
        if (shaderCheckFailed != null && shaderCheckFailed) return false;
        try {
            if (isShaders == null) {
                Class<?> c;
                try {
                    c = Class.forName("net.optifine.Config");
                } catch (ClassNotFoundException e) {
                    c = Class.forName("Config");
                }
                isShaders = c.getMethod("isShaders");
            }
            Object r = isShaders.invoke(null);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            shaderCheckFailed = Boolean.TRUE;
            return false;
        }
    }

    private static void chat(String msg) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player != null) mc.player.sendMessage(new TextComponentString(msg));
    }
}
