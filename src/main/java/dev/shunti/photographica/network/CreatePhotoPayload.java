package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.component.CameraSettings;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.UUID;

/**
 * C2S: client successfully wrote a PNG; ask server to create the corresponding photo item.
 */
public record CreatePhotoPayload(UUID id, CameraSettings settings) implements CustomPayload {
	public static final CustomPayload.Id<CreatePhotoPayload> ID =
			new CustomPayload.Id<>(Identifier.of(Photographica.MOD_ID, "create_photo"));

	public static final PacketCodec<RegistryByteBuf, CreatePhotoPayload> CODEC = new PacketCodec<>() {
		@Override
		public CreatePhotoPayload decode(RegistryByteBuf buf) {
			long hi = buf.readLong();
			long lo = buf.readLong();
			UUID id = new UUID(hi, lo);
			CameraSettings settings = CameraSettings.PACKET_CODEC.decode(buf);
			return new CreatePhotoPayload(id, settings);
		}

		@Override
		public void encode(RegistryByteBuf buf, CreatePhotoPayload v) {
			buf.writeLong(v.id.getMostSignificantBits());
			buf.writeLong(v.id.getLeastSignificantBits());
			CameraSettings.PACKET_CODEC.encode(buf, v.settings);
		}
	};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
