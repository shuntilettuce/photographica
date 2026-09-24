package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** C2S: player wants to load an SD card from inventory into held camera. */
public record LoadSdCardPayload() implements CustomPayload {
    public static final CustomPayload.Id<LoadSdCardPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Photographica.MOD_ID, "load_sd_card"));

    public static final PacketCodec<RegistryByteBuf, LoadSdCardPayload> CODEC =
            PacketCodec.unit(new LoadSdCardPayload());

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
