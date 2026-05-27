package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.component.CameraSettings;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.UUID;

public record TakeFilmPhotoFromArmorStandPayload(UUID id, CameraSettings settings, int entityId) implements CustomPayload {
	public static final CustomPayload.Id<TakeFilmPhotoFromArmorStandPayload> ID =
			new CustomPayload.Id<>(Identifier.of(Photographica.MOD_ID, "take_film_photo_from_armor_stand"));

	public static final PacketCodec<RegistryByteBuf, TakeFilmPhotoFromArmorStandPayload> CODEC =
			new PacketCodec<>() {
				@Override
				public TakeFilmPhotoFromArmorStandPayload decode(RegistryByteBuf buf) {
					UUID id = buf.readUuid();
					CameraSettings settings = CameraSettings.PACKET_CODEC.decode(buf);
					int entityId = buf.readInt();
					return new TakeFilmPhotoFromArmorStandPayload(id, settings, entityId);
				}

				@Override
				public void encode(RegistryByteBuf buf, TakeFilmPhotoFromArmorStandPayload value) {
					buf.writeUuid(value.id());
					CameraSettings.PACKET_CODEC.encode(buf, value.settings());
					buf.writeInt(value.entityId());
				}
			};

	@Override
	public Id<? extends CustomPayload> getId() { return ID; }
}
