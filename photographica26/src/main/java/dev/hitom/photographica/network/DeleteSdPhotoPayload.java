package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** C2S: delete a photo from the SD card currently loaded in the held camera. */
public record DeleteSdPhotoPayload(UUID photoId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DeleteSdPhotoPayload> ID =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, "delete_sd_photo"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DeleteSdPhotoPayload> CODEC = new StreamCodec<>() {
        @Override
        public DeleteSdPhotoPayload decode(RegistryFriendlyByteBuf buf) {
            return new DeleteSdPhotoPayload(new UUID(buf.readLong(), buf.readLong()));
        }
        @Override
        public void encode(RegistryFriendlyByteBuf buf, DeleteSdPhotoPayload v) {
            buf.writeLong(v.photoId.getMostSignificantBits());
            buf.writeLong(v.photoId.getLeastSignificantBits());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return ID; }
}
