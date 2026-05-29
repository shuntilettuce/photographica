package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Sent by the client when the player right-clicks an armor stand with a camera in hand.
 * The server equips the camera to the stand's main hand and gives back any item already held.
 */
public record EquipCameraToArmorStandPayload(int entityId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<EquipCameraToArmorStandPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Photographica.MOD_ID, "equip_camera_to_armor_stand"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EquipCameraToArmorStandPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public EquipCameraToArmorStandPayload decode(RegistryFriendlyByteBuf buf) {
                    return new EquipCameraToArmorStandPayload(buf.readInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, EquipCameraToArmorStandPayload value) {
                    buf.writeInt(value.entityId());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() { return ID; }
}
