package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.component.CameraSettings;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * C2S: client successfully wrote a PNG; ask server to create the corresponding photo item.
 */
public record CreatePhotoPayload(UUID id, CameraSettings settings) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<CreatePhotoPayload> ID =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Photographica.MOD_ID, "create_photo"));

	public static final StreamCodec<RegistryFriendlyByteBuf, CreatePhotoPayload> CODEC = new StreamCodec<>() {
		@Override
		public CreatePhotoPayload decode(RegistryFriendlyByteBuf buf) {
			long hi = buf.readLong();
			long lo = buf.readLong();
			UUID id = new UUID(hi, lo);
			CameraSettings settings = CameraSettings.PACKET_CODEC.decode(buf);
			return new CreatePhotoPayload(id, settings);
		}

		@Override
		public void encode(RegistryFriendlyByteBuf buf, CreatePhotoPayload v) {
			buf.writeLong(v.id.getMostSignificantBits());
			buf.writeLong(v.id.getLeastSignificantBits());
			CameraSettings.PACKET_CODEC.encode(buf, v.settings);
		}
	};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}
}
