package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.component.CameraSettings;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

public record CreatePhotoFromArmorStandPayload(UUID id, CameraSettings settings, int entityId) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<CreatePhotoFromArmorStandPayload> ID =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Photographica.MOD_ID, "create_photo_from_armor_stand"));

	public static final StreamCodec<RegistryFriendlyByteBuf, CreatePhotoFromArmorStandPayload> CODEC =
			new StreamCodec<>() {
				@Override
				public CreatePhotoFromArmorStandPayload decode(RegistryFriendlyByteBuf buf) {
					UUID id = buf.readUUID();
					CameraSettings settings = CameraSettings.PACKET_CODEC.decode(buf);
					int entityId = buf.readInt();
					return new CreatePhotoFromArmorStandPayload(id, settings, entityId);
				}

				@Override
				public void encode(RegistryFriendlyByteBuf buf, CreatePhotoFromArmorStandPayload value) {
					buf.writeUUID(value.id());
					CameraSettings.PACKET_CODEC.encode(buf, value.settings());
					buf.writeInt(value.entityId());
				}
			};

	@Override
	public Type<? extends CustomPacketPayload> type() { return ID; }
}
