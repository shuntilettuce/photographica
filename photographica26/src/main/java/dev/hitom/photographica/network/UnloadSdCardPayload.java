package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C2S: player wants to unload the SD card from held camera. */
public record UnloadSdCardPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<UnloadSdCardPayload> ID =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, "unload_sd_card"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UnloadSdCardPayload> CODEC =
            StreamCodec.unit(new UnloadSdCardPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() { return ID; }
}
