package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C2S: player wants to load an SD card from inventory into held camera. */
public record LoadSdCardPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<LoadSdCardPayload> ID =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, "load_sd_card"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LoadSdCardPayload> CODEC =
            StreamCodec.unit(new LoadSdCardPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() { return ID; }
}
