package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.UUID;

/** S2C: answers {@link RequestPhotoPayload} when the server has no copy of that photo either
 *  (e.g. the photographer's upload never completed). Lets the client stop waiting and show
 *  "not found" instead of hanging in a "loading" state forever. */
public record PhotoNotFoundPayload(UUID id) implements CustomPayload {
    public static final CustomPayload.Id<PhotoNotFoundPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Photographica.MOD_ID, "photo_not_found"));

    public static final PacketCodec<RegistryByteBuf, PhotoNotFoundPayload> CODEC = new PacketCodec<>() {
        @Override
        public PhotoNotFoundPayload decode(RegistryByteBuf buf) {
            return new PhotoNotFoundPayload(new UUID(buf.readLong(), buf.readLong()));
        }
        @Override
        public void encode(RegistryByteBuf buf, PhotoNotFoundPayload v) {
            buf.writeLong(v.id.getMostSignificantBits());
            buf.writeLong(v.id.getLeastSignificantBits());
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
