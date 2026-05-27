package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.component.CameraSettings;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.UUID;

public record CreatePhotoFromArmorStandPayload(UUID id, CameraSettings settings, int entityId) implements CustomPayload {
	public static final CustomPayload.Id<CreatePhotoFromArmorStandPayload> ID =
			new CustomPayload.Id<>(Identifier.of(Photographica.MOD_ID, "create_photo_from_armor_stand"));

	public static final PacketCodec<RegistryByteBuf, CreatePhotoFromArmorStandPayload> CODEC =
			new PacketCodec<>() {
				@Override
				public CreatePhotoFromArmorStandPayload decode(RegistryByteBuf buf) {
					UUID id = buf.readUuid();
					CameraSettings settings = CameraSettings.PACKET_CODEC.decode(buf);
					int entityId = buf.readInt();
					return new CreatePhotoFromArmorStandPayload(id, settings, entityId);
				}

				@Override
				public void encode(RegistryByteBuf buf, CreatePhotoFromArmorStandPayload value) {
					buf.writeUuid(value.id());
					CameraSettings.PACKET_CODEC.encode(buf, value.settings());
					buf.writeInt(value.entityId());
				}
			};

	@Override
	public Id<? extends CustomPayload> getId() { return ID; }
}
