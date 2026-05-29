package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.component.CameraSettings;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record UpdateArmorStandCameraPayload(int entityId, CameraSettings settings) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<UpdateArmorStandCameraPayload> ID =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Photographica.MOD_ID, "update_armor_stand_camera"));

	public static final StreamCodec<RegistryFriendlyByteBuf, UpdateArmorStandCameraPayload> CODEC =
			new StreamCodec<>() {
				@Override
				public UpdateArmorStandCameraPayload decode(RegistryFriendlyByteBuf buf) {
					int entityId = buf.readInt();
					CameraSettings settings = CameraSettings.PACKET_CODEC.decode(buf);
					return new UpdateArmorStandCameraPayload(entityId, settings);
				}

				@Override
				public void encode(RegistryFriendlyByteBuf buf, UpdateArmorStandCameraPayload value) {
					buf.writeInt(value.entityId());
					CameraSettings.PACKET_CODEC.encode(buf, value.settings());
				}
			};

	@Override
	public Type<? extends CustomPacketPayload> type() { return ID; }
}
