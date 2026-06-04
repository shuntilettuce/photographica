package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.component.CameraSettings;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

public record TakeFilmPhotoFromArmorStandPayload(UUID id, CameraSettings settings, int entityId) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<TakeFilmPhotoFromArmorStandPayload> ID =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Photographica.MOD_ID, "take_film_photo_from_armor_stand"));

	public static final StreamCodec<RegistryFriendlyByteBuf, TakeFilmPhotoFromArmorStandPayload> CODEC =
			new StreamCodec<>() {
				@Override
				public TakeFilmPhotoFromArmorStandPayload decode(RegistryFriendlyByteBuf buf) {
					UUID id = buf.readUUID();
					CameraSettings settings = CameraSettings.PACKET_CODEC.decode(buf);
					int entityId = buf.readInt();
					return new TakeFilmPhotoFromArmorStandPayload(id, settings, entityId);
				}

				@Override
				public void encode(RegistryFriendlyByteBuf buf, TakeFilmPhotoFromArmorStandPayload value) {
					buf.writeUUID(value.id());
					CameraSettings.PACKET_CODEC.encode(buf, value.settings());
					buf.writeInt(value.entityId());
				}
			};

	@Override
	public Type<? extends CustomPacketPayload> type() { return ID; }
}
