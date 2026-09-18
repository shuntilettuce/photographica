package dev.shunti.snapmatica.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Resolves a translation key against the client's active language.
 *
 * The overlay draws plain strings rather than Text/Component objects, so the
 * key has to be resolved here instead of at draw time.
 */
@Environment(EnvType.CLIENT)
public final class Lang {
    private Lang() {}

    public static String tr(String key) {
        //? if >=26 {
        /*return net.minecraft.network.chat.Component.translatable(key).getString();*/
        //?} else {
        return net.minecraft.text.Text.translatable(key).getString();
        //?}
    }
}
