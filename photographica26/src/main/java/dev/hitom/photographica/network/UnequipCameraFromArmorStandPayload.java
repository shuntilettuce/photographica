package dev.hitom.photographica.network;

import dev.hitom.photographica.Photographica;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Sent by the client when the player presses "取り出す" in a camera settings screen
 * opened from an armor stand. The server finds the camera on the stand, removes it,
 * and places it in the player's inventory (or drops it if full).
 */
public record UnequipCameraFromArmorStandPayload(int entityId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<UnequipCameraFromArmorStandPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Photographica.MOD_ID, "unequip_camera_from_armor_stand"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UnequipCameraFromArmorStandPayload> CODEC =
            new StreamCodec<>() {
                @Override
                public UnequipCameraFromArmorStandPayload decode(RegistryFriendlyByteBuf buf) {
                    return new UnequipCameraFromArmorStandPayload(buf.readInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, UnequipCameraFromArmorStandPayload value) {
                    buf.writeInt(value.entityId());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() { return ID; }
}
