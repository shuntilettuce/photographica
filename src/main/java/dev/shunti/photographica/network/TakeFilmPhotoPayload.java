package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.component.CameraSettings;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.UUID;

/**
 * C2S: a film-camera frame was captured to disk; ask the server to append the
 * exposure to the loaded film roll, increment used count, and unwind the camera.
 */
public record TakeFilmPhotoPayload(UUID id, CameraSettings settings) implements CustomPayload {
	public static final CustomPayload.Id<TakeFilmPhotoPayload> ID =
			new CustomPayload.Id<>(Identifier.of(Photographica.MOD_ID, "take_film_photo"));

	public static final PacketCodec<RegistryByteBuf, TakeFilmPhotoPayload> CODEC = new PacketCodec<>() {
		@Override
		public TakeFilmPhotoPayload decode(RegistryByteBuf buf) {
			long hi = buf.readLong();
			long lo = buf.readLong();
			return new TakeFilmPhotoPayload(new UUID(hi, lo), CameraSettings.PACKET_CODEC.decode(buf));
		}

		@Override
		public void encode(RegistryByteBuf buf, TakeFilmPhotoPayload v) {
			buf.writeLong(v.id.getMostSignificantBits());
			buf.writeLong(v.id.getLeastSignificantBits());
			CameraSettings.PACKET_CODEC.encode(buf, v.settings);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() { return ID; }
}
