package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.component.CameraSettings;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record UpdateCameraSettingsPayload(CameraSettings settings) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<UpdateCameraSettingsPayload> ID =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Photographica.MOD_ID, "update_camera_settings"));

	public static final StreamCodec<RegistryFriendlyByteBuf, UpdateCameraSettingsPayload> CODEC =
			new StreamCodec<>() {
				@Override
				public UpdateCameraSettingsPayload decode(RegistryFriendlyByteBuf buf) {
					return new UpdateCameraSettingsPayload(CameraSettings.PACKET_CODEC.decode(buf));
				}

				@Override
				public void encode(RegistryFriendlyByteBuf buf, UpdateCameraSettingsPayload value) {
					CameraSettings.PACKET_CODEC.encode(buf, value.settings());
				}
			};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return ID;
	}
}
