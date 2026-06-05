package dev.hitom.photographica.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets the free-view tripod recorder temporarily redirect world rendering into an
 * off-screen framebuffer by swapping {@link MinecraftClient}'s framebuffer field,
 * then restore it.  Both classic and 1.21.11 WorldRenderer call mc.getFramebuffer()
 * at render time (not init time), so the swap redirects the tripod render pass.
 *
 * 1.21.11 also needs direct field access to the private {@code cameraEntity} field
 * so the camera swap during the tripod pass doesn't trigger onCameraEntitySet()
 * chunk-reload churn at 24-30 fps.
 */
//? if <1.21.11 {
@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {
    @Accessor("framebuffer")
    @Mutable
    void photographica$setFramebuffer(Framebuffer framebuffer);
}
//?}
//? if >=1.21.11 {
/*@Mixin(MinecraftClient.class)
public interface MinecraftClientAccessor {
    @Accessor("framebuffer")
    @Mutable
    void photographica$setFramebuffer(Framebuffer framebuffer);

    @Accessor("cameraEntity")
    Entity photographica$getCameraEntityDirect();

    @Accessor("cameraEntity")
    @Mutable
    void photographica$setCameraEntityDirect(Entity entity);
}*/
//?}
