package dev.hitom.photographica.network;

import dev.hitom.photographica.component.CameraSettings;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.UUID;

/** C2S: "the photo with this UUID was captured from the camera mounted on this drone" —
 *  mirrors {@link CreatePhotoFromArmorStandPayload}, but the server resolves a
 *  {@code DroneEntity} and its {@code TrackedData<ItemStack>}-held camera instead of an
 *  {@code ArmorStandEntity}'s vanilla equipment slots. */
public record CreatePhotoFromDronePayload(UUID id, CameraSettings settings, int droneEntityId) implements CustomPayload {
    public static final CustomPayload.Id<CreatePhotoFromDronePayload> ID =
            new CustomPayload.Id<>(Identifier.of("photographica", "create_photo_from_drone"));

    public static final PacketCodec<RegistryByteBuf, CreatePhotoFromDronePayload> CODEC = new PacketCodec<>() {
        @Override
        public CreatePhotoFromDronePayload decode(RegistryByteBuf buf) {
            UUID id = new UUID(buf.readLong(), buf.readLong());
            CameraSettings settings = CameraSettings.PACKET_CODEC.decode(buf);
            int droneEntityId = buf.readInt();
            return new CreatePhotoFromDronePayload(id, settings, droneEntityId);
        }
        @Override
        public void encode(RegistryByteBuf buf, CreatePhotoFromDronePayload v) {
            buf.writeLong(v.id.getMostSignificantBits());
            buf.writeLong(v.id.getLeastSignificantBits());
            CameraSettings.PACKET_CODEC.encode(buf, v.settings);
            buf.writeInt(v.droneEntityId);
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
