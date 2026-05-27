package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** C2S: player wants to unload the SD card from held camera. */
public record UnloadSdCardPayload() implements CustomPayload {
    public static final CustomPayload.Id<UnloadSdCardPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Photographica.MOD_ID, "unload_sd_card"));

    public static final PacketCodec<RegistryByteBuf, UnloadSdCardPayload> CODEC =
            PacketCodec.unit(new UnloadSdCardPayload());

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
