package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.UUID;

/**
 * S2C: one chunk of a requested photo's PNG bytes, answering {@link RequestPhotoPayload}. Same
 * shape as {@link UploadPhotoChunkPayload} — chunked in both directions for the same reason.
 * The receiving client reassembles and writes the result to its own local cache, so a photo
 * only ever needs fetching once per client.
 */
public record DownloadPhotoChunkPayload(UUID id, int chunkIndex, int totalChunks, byte[] data) implements CustomPayload {
    public static final CustomPayload.Id<DownloadPhotoChunkPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Photographica.MOD_ID, "download_photo_chunk"));

    public static final PacketCodec<RegistryByteBuf, DownloadPhotoChunkPayload> CODEC = new PacketCodec<>() {
        @Override
        public DownloadPhotoChunkPayload decode(RegistryByteBuf buf) {
            UUID id = new UUID(buf.readLong(), buf.readLong());
            int chunkIndex = buf.readInt();
            int totalChunks = buf.readInt();
            int len = buf.readInt();
            // Same bound as the upload direction, for the same reason — a client shouldn't be
            // OOM-able by whatever server it happens to connect to either.
            if (len < 0 || len > PhotoChunkAssembler.CHUNK_SIZE) {
                throw new IllegalArgumentException("photo chunk length out of range: " + len);
            }
            byte[] data = new byte[len];
            buf.readBytes(data);
            return new DownloadPhotoChunkPayload(id, chunkIndex, totalChunks, data);
        }
        @Override
        public void encode(RegistryByteBuf buf, DownloadPhotoChunkPayload v) {
            buf.writeLong(v.id.getMostSignificantBits());
            buf.writeLong(v.id.getLeastSignificantBits());
            buf.writeInt(v.chunkIndex);
            buf.writeInt(v.totalChunks);
            buf.writeInt(v.data.length);
            buf.writeBytes(v.data);
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
