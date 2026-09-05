package dev.shunti.snapmatica.client.mixin;

import net.minecraft.client.render.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes the projection matrix the world is ACTUALLY rendered with, so the EVF depth
 * linearisation reads the same far plane that produced the depth buffer.
 *
 * <p>On 1.21.11 that is {@code getProjectionMatrix}, which is private — hence this invoker.
 * It is a different method from the public {@code getBasicProjectionMatrix}, which only
 * reconstructs a matrix from vanilla parameters and therefore cannot see a far-plane
 * extension a LOD mod (Voxy, DH) applies deeper in the pipeline.
 *
 * <p>Older versions have no such split — {@code renderWorld} uses
 * {@code getBasicProjectionMatrix} directly, so that is the correct target there. Every
 * branch below names a method that exists on its version, so the mixin applies cleanly
 * everywhere and callers need no version guard of their own (1.21.1 takes a double, which
 * a float argument widens to).
 */
@Mixin(GameRenderer.class)
public interface GameRendererInvoker {
    //? if >=1.21.10 {
    @Invoker("getProjectionMatrix")
    Matrix4f snapmatica$worldProjection(float fovDegrees);
    //?} elif >=1.21.2 {
    /*@Invoker("getBasicProjectionMatrix")
    Matrix4f snapmatica$worldProjection(float fovDegrees);
    *///?} else {
    /*@Invoker("getBasicProjectionMatrix")
    Matrix4f snapmatica$worldProjection(double fovDegrees);
    *///?}
}
