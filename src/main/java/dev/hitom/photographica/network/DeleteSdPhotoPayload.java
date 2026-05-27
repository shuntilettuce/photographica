package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.UUID;

/** C2S: delete a photo from the SD card currently loaded in the held camera. */
public record DeleteSdPhotoPayload(UUID photoId) implements CustomPayload {
    public static final CustomPayload.Id<DeleteSdPhotoPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Photographica.MOD_ID, "delete_sd_photo"));

    public static final PacketCodec<RegistryByteBuf, DeleteSdPhotoPayload> CODEC = new PacketCodec<>() {
        @Override
        public DeleteSdPhotoPayload decode(RegistryByteBuf buf) {
            return new DeleteSdPhotoPayload(new UUID(buf.readLong(), buf.readLong()));
        }
        @Override
        public void encode(RegistryByteBuf buf, DeleteSdPhotoPayload v) {
            buf.writeLong(v.photoId.getMostSignificantBits());
            buf.writeLong(v.photoId.getLeastSignificantBits());
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
