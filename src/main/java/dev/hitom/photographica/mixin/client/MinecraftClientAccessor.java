package dev.hitom.photographica.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets the free-view tripod recorder temporarily redirect world rendering into an
 * off-screen framebuffer by swapping {@link MinecraftClient}'s {@code framebuffer}
 * field, then restore it.  {@code WorldRenderer.render()} binds whatever
 * {@code mc.getFramebuffer()} returns, so swapping the field redirects the second
 * (tripod) render pass without disturbing the player's on-screen view.
 *
 * Classic (&lt;1.21.11) only — the 1.21.11 render pipeline targets GpuTextures and
 * uses the existing locked-camera tripod path instead.
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
}*/
//?}
