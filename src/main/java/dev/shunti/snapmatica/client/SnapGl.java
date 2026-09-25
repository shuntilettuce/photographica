package dev.shunti.snapmatica.client;

import com.mojang.blaze3d.textures.GpuTexture;
import net.neoforged.neoforge.client.blaze3d.validation.ValidationGpuTexture;

/**
 * NeoForge wraps every GpuTexture in a validation layer (on in dev runs, and whenever the
 * validation option is set), so an {@code instanceof GlTexture} check on the wrapper fails
 * and the GL id is never reached. Unwrap before looking for the GL texture.
 */
final class SnapGl {
    static GpuTexture unwrap(GpuTexture t) {
        while (t instanceof ValidationGpuTexture v) t = v.getRealTexture();
        return t;
    }
}
