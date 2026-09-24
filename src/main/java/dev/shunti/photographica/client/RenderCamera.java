package dev.hitom.photographica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Vec3d;

/**
 * Where the photograph is actually taken from — the render camera, not {@code mc.player}'s eye.
 * They coincide for an ordinary handheld shot, but diverge whenever something else is driving
 * the camera: riding a drone (vanilla's own rider-camera follows the vehicle automatically) or
 * a tripod recording (which redirects {@code mc.cameraEntity} to the armor stand). Reading from
 * here instead of the player directly is what lets autofocus (mob tracking, video AF) and the
 * capture origin follow either case with no special-casing needed at the call site.
 */
@Environment(EnvType.CLIENT)
public final class RenderCamera {
    private RenderCamera() {}

    public static Vec3d pos(MinecraftClient mc) {
        Camera camera = mc.gameRenderer.getCamera();
        //? if >=1.21.10 {
        /*return camera.getCameraPos();
        *///?} else {
        return camera.getPos();
        //?}
    }

    public static Vec3d look(MinecraftClient mc) {
        Camera camera = mc.gameRenderer.getCamera();
        return Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
    }
}
