package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.component.CameraSettings;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * C2S: a film-camera frame was captured to disk; ask the server to append the
 * exposure to the loaded film roll, increment used count, and unwind the camera.
 */
public record TakeFilmPhotoPayload(UUID id, CameraSettings settings) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<TakeFilmPhotoPayload> ID =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Photographica.MOD_ID, "take_film_photo"));

	public static final StreamCodec<RegistryFriendlyByteBuf, TakeFilmPhotoPayload> CODEC = new StreamCodec<>() {
		@Override
		public TakeFilmPhotoPayload decode(RegistryFriendlyByteBuf buf) {
			long hi = buf.readLong();
			long lo = buf.readLong();
			return new TakeFilmPhotoPayload(new UUID(hi, lo), CameraSettings.PACKET_CODEC.decode(buf));
		}

		@Override
		public void encode(RegistryFriendlyByteBuf buf, TakeFilmPhotoPayload v) {
			buf.writeLong(v.id.getMostSignificantBits());
			buf.writeLong(v.id.getLeastSignificantBits());
			CameraSettings.PACKET_CODEC.encode(buf, v.settings);
		}
	};

	@Override
	public Type<? extends CustomPacketPayload> type() { return ID; }
}
