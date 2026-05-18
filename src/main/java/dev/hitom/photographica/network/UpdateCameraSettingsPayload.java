package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.component.CameraSettings;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record UpdateCameraSettingsPayload(CameraSettings settings) implements CustomPayload {
	public static final CustomPayload.Id<UpdateCameraSettingsPayload> ID =
			new CustomPayload.Id<>(Identifier.of(Photographica.MOD_ID, "update_camera_settings"));

	public static final PacketCodec<RegistryByteBuf, UpdateCameraSettingsPayload> CODEC =
			new PacketCodec<>() {
				@Override
				public UpdateCameraSettingsPayload decode(RegistryByteBuf buf) {
					return new UpdateCameraSettingsPayload(CameraSettings.PACKET_CODEC.decode(buf));
				}

				@Override
				public void encode(RegistryByteBuf buf, UpdateCameraSettingsPayload value) {
					CameraSettings.PACKET_CODEC.encode(buf, value.settings());
				}
			};

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
