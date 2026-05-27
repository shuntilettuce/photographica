package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Sent by the client when the player right-clicks an armor stand with a camera in hand.
 * The server equips the camera to the stand's main hand and gives back any item already held.
 */
public record EquipCameraToArmorStandPayload(int entityId) implements CustomPayload {
    public static final CustomPayload.Id<EquipCameraToArmorStandPayload> ID =
            new CustomPayload.Id<>(Identifier.of(Photographica.MOD_ID, "equip_camera_to_armor_stand"));

    public static final PacketCodec<RegistryByteBuf, EquipCameraToArmorStandPayload> CODEC =
            new PacketCodec<>() {
                @Override
                public EquipCameraToArmorStandPayload decode(RegistryByteBuf buf) {
                    return new EquipCameraToArmorStandPayload(buf.readInt());
                }

                @Override
                public void encode(RegistryByteBuf buf, EquipCameraToArmorStandPayload value) {
                    buf.writeInt(value.entityId());
                }
            };

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
