package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import dev.hitom.photographica.component.CameraSettings;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record UpdateArmorStandCameraPayload(int entityId, CameraSettings settings) implements CustomPayload {
	public static final CustomPayload.Id<UpdateArmorStandCameraPayload> ID =
			new CustomPayload.Id<>(Identifier.of(Photographica.MOD_ID, "update_armor_stand_camera"));

	public static final PacketCodec<RegistryByteBuf, UpdateArmorStandCameraPayload> CODEC =
			new PacketCodec<>() {
				@Override
				public UpdateArmorStandCameraPayload decode(RegistryByteBuf buf) {
					int entityId = buf.readInt();
					CameraSettings settings = CameraSettings.PACKET_CODEC.decode(buf);
					return new UpdateArmorStandCameraPayload(entityId, settings);
				}

				@Override
				public void encode(RegistryByteBuf buf, UpdateArmorStandCameraPayload value) {
					buf.writeInt(value.entityId());
					CameraSettings.PACKET_CODEC.encode(buf, value.settings());
				}
			};

	@Override
	public Id<? extends CustomPayload> getId() { return ID; }
}
