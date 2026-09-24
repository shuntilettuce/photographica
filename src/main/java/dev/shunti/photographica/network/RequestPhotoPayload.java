package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.UUID;

/**
 * C2S: "I don't have this photo's PNG locally, please send it" — sent by
 * {@code PhotoTextureCache.getOrLoad} on a local cache miss. The server answers with either
 * {@link DownloadPhotoChunkPayload} chunks or {@link PhotoNotFoundPayload}.
 */
public record RequestPhotoPayload(UUID id) implements CustomPayload {
    public static final CustomPayload.Id<RequestPhotoPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Photographica.MOD_ID, "request_photo"));

    public static final PacketCodec<RegistryByteBuf, RequestPhotoPayload> CODEC = new PacketCodec<>() {
        @Override
        public RequestPhotoPayload decode(RegistryByteBuf buf) {
            return new RequestPhotoPayload(new UUID(buf.readLong(), buf.readLong()));
        }
        @Override
        public void encode(RegistryByteBuf buf, RequestPhotoPayload v) {
            buf.writeLong(v.id.getMostSignificantBits());
            buf.writeLong(v.id.getLeastSignificantBits());
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
