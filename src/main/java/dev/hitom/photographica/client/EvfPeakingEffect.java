package dev.hitom.photographica.client;

import dev.hitom.photographica.Photographica;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.PostEffectProcessor;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Identifier;

import java.io.IOException;

public final class EvfPeakingEffect {

    private static PostEffectProcessor effect = null;
    private static int lastW = -1, lastH = -1;

    private static final Identifier EFFECT_ID =
            Identifier.of("minecraft", "shaders/post/evf_peaking.json");

    private EvfPeakingEffect() {}

    public static void apply(MinecraftClient mc, float aperture, RenderTickCounter tickCounter) {
        if (aperture >= 8.0f) return;

        int w = mc.getWindow().getFramebufferWidth();
        int h = mc.getWindow().getFramebufferHeight();

        if (effect == null || lastW != w || lastH != h) {
            disposeEffect();
            try {
                effect = new PostEffectProcessor(
                        mc.getTextureManager(),
                        mc.getResourceManager(),
                        mc.getFramebuffer(),
                        EFFECT_ID
                );
                effect.setupDimensions(w, h);
                lastW = w;
                lastH = h;
            } catch (IOException e) {
                Photographica.LOGGER.error("Failed to load EVF focus peaking shader", e);
                effect = null;
                return;
            }
        }

        effect.render(tickCounter.getLastFrameDuration());
        mc.getFramebuffer().beginWrite(false);
    }

    public static void invalidate() {
        disposeEffect();
    }

    private static void disposeEffect() {
        if (effect != null) {
            effect.close();
            effect = null;
        }
        lastW = -1;
        lastH = -1;
    }
}
