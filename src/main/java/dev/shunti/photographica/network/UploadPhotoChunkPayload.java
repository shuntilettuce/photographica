package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.UUID;

/**
 * C2S: one chunk of a photo's raw PNG bytes, sent right after the client finishes writing its
 * own local copy. The server reassembles chunks by {@code id} and persists the result under the
 * world save directory, becoming the canonical copy any other player's client can fetch later
 * (see {@link RequestPhotoPayload}). Chunked rather than sent whole to keep any single packet
 * small regardless of the photo's compressed size.
 */
public record UploadPhotoChunkPayload(UUID id, int chunkIndex, int totalChunks, byte[] data) implements CustomPayload {
    public static final CustomPayload.Id<UploadPhotoChunkPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Photographica.MOD_ID, "upload_photo_chunk"));

    public static final PacketCodec<RegistryByteBuf, UploadPhotoChunkPayload> CODEC = new PacketCodec<>() {
        @Override
        public UploadPhotoChunkPayload decode(RegistryByteBuf buf) {
            UUID id = new UUID(buf.readLong(), buf.readLong());
            int chunkIndex = buf.readInt();
            int totalChunks = buf.readInt();
            int len = buf.readInt();
            // Bounded before allocating: this length comes straight off the wire from a client,
            // and `new byte[Integer.MAX_VALUE]` would OOM the server on the netty thread before
            // any handler ever saw the packet (a negative value throws instead). Failing decode
            // is the safe outcome — vanilla disconnects that one connection rather than dying.
            if (len < 0 || len > PhotoChunkAssembler.CHUNK_SIZE) {
                throw new IllegalArgumentException("photo chunk length out of range: " + len);
            }
            byte[] data = new byte[len];
            buf.readBytes(data);
            return new UploadPhotoChunkPayload(id, chunkIndex, totalChunks, data);
        }
        @Override
        public void encode(RegistryByteBuf buf, UploadPhotoChunkPayload v) {
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
